package stream.cliamp.mobile.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import stream.cliamp.mobile.CliampApp
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.widget.CliampWidgetReceiver

/**
 * The UI's handle on playback. Transport goes through a MediaController rather
 * than straight to the ExoPlayer, so the app, the notification and any
 * Bluetooth remote all drive the same state machine.
 */
@UnstableApi
class PlayerConnection(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _queue = MutableStateFlow<List<Station>>(emptyList())
    private val _queueIndex = MutableStateFlow(-1)

    /**
     * The full list a play originated from. When that list is huge - hundreds
     * of local songs - [_queue] only holds a bounded window starting at the
     * tapped track, so touching a song queues a short "up next" run instead of
     * dumping the whole library in. [_source] keeps the whole list so prev /
     * next and the auto-roll-forward can keep walking it. For a short list
     * [_source] and [_queue] are the same list.
     */
    private var _source: List<Station> = emptyList()

    /** The linear list a play originated from, before any shuffle reordering.
     * Shuffle reorders a copy of it into [_source]; turning shuffle off restores
     * [_source] to this so prev / next walk the natural playlist order again.
     */
    private var _baseSource: List<Station> = emptyList()

    /**
     * List used for prev / next before anything has played this session (e.g. at
     * launch, when the mini player shows the last-played song from history).
     * The root supplies recent history so stepping works from that shown song.
     */
    private var _fallbackSource: List<Station> = emptyList()

    /** Set by the root with recent history so prev/next work from a fresh launch. */
    fun setFallbackSource(list: List<Station>) { _fallbackSource = list }

    // If the app is opened straight into the notification (or nothing has UI-composed
    // yet), there is no root to seed the fallback list, so prime it from history and
    // favourites ourselves. The root's seed wins when it arrives.
    init {
        val app = context.applicationContext as CliampApp
        scope.launch {
            val prefs = app.prefs
            val history = prefs.history.first()
            val favs = prefs.favorites.first()
            if (_fallbackSource.isEmpty()) {
                _fallbackSource = history.ifEmpty { favs }
            }
        }
    }

    /** Whether shuffled playback is switched on. */
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    /**
     * The (re)shuffled copy of [_baseSource] currently being played so toggling
     * shuffle off can restore the original order; null when shuffle is off.
     */
    private var _shuffledSource: List<Station>? = null

    /**
     * Logical index (into [_source]) of the first item held in [_queue]. When
     * a queue is huge the tapped track plus a small tail are queued instead of
     * the whole thing, so play starts instantly; this offset says where that
     * window begins inside the source list. For a short queue it is 0.
     */
    private var windowBase = 0

    /** Pending window-roll-forward job, cancelled if the window advances sooner. */
    private var _extending: Job? = null

    /**
     * Raised while [play] is rebuilding the Media3 queue. [sync] maps Media3's
     * window index back onto the new logical queue, but during the transition
     * Media3 still holds the previous items, so a poller tick can collide the
     * old window with the fresh [_queue] and jump to - and publish - the wrong
     * track (e.g. tapping a song in the middle of a list plays the first item).
     * While the queue swap is in flight sync stays out of that bookkeeping and
     * only refreshes transport state; [play] finalises the index itself.
     */
    @Volatile
    private var swapping = false

    /**
     * Coalesces rapid transport taps without lagging a plain tap. Each step
     * keeps an absolute target in [_source] in [_navPending]. A burst of next /
     * prev fires the FIRST tap immediately (isolated taps thus respond at once)
     * and then restarts a short debounce timer on each follow-up, so the burst
     * settles on the LAST tapped song - the songs in between are never (re)played
     * and playback isn't restarted per tap. [_navTimerJob] only waits and hands
     * off to play(); the media job itself lives on [_navJob] and is unaffected.
     */
    private var _navJob: Job? = null
    private var _navTimerJob: Job? = null
    private var _navPending: Int? = null
    private var _lastNavTapMs = 0L

    private val NAV_DEBOUNCE_MS = 180L

    /** Queues larger than this are played lazily from a window, not in full. */
    private val WINDOW = 60

    /** How often a playing episode's position reaches the database. */
    private val PROGRESS_INTERVAL = 5_000L

    /** The list prev/next walks. Set whenever the user plays from a list. */
    val queue: StateFlow<List<Station>> = _queue.asStateFlow()

    /** Index of the currently-playing station in [queue], or -1. */
    val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    val currentQueue: List<Station> get() = _queue.value

    /** Called once the controller is live, if the user asked for auto-resume. */
    var onReady: (() -> Unit)? = null

    /**
     * Where a podcast episode should start, in millis. Set from Application,
     * the way StreamResolver's provider hook is: playback needs the answer but
     * has no business holding a database handle to get it. Returns 0 for
     * everything else, which is every station radio ever plays.
     */
    var resumeLookup: (suspend (Station) -> Long)? = null

    /** Receives (station, position, duration) for episodes as they play. */
    var progressSink: (suspend (Station, Long, Long) -> Unit)? = null

    private var lastProgressWrite = 0L

    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sync()
            })
            sync()
            onReady?.invoke()
        }, MoreExecutors.directExecutor())

        scope.launch {
            while (true) {
                delay(500)
                sync()
            }
        }
    }

    private fun sync() {
        val c = controller ?: return

        val q = _queue.value
        if (!swapping && c.mediaItemCount > 0 && q.isNotEmpty()) {
            val playerIndex = c.currentMediaItemIndex.coerceIn(0, c.mediaItemCount - 1)
            // Radio (or a lone track) is pushed as a single Media3 item while the
            // panel still shows a full window around it, so Media3's index can't
            // be mapped straight onto [_queue] - that would repoint the active
            // item back to the head of the list. Only read back the index when
            // Media3 actually holds the whole queue.
            val oneToOne = c.mediaItemCount == q.size
            val logical = if (oneToOne) {
                // Resolve by the playing item's id so the model stays the source
                // of truth even when a shuffle toggle left Media3 in a different
                // order than the (new) shuffled queue.
                val curId = c.getMediaItemAt(playerIndex).mediaId
                q.indexOfFirst { it.id == curId }.takeIf { it >= 0 } ?: playerIndex
            } else _queueIndex.value
            // Only publish when the resolved panel item has actually changed.
            if (logical != _queueIndex.value && q.getOrNull(logical)?.id != q.getOrNull(_queueIndex.value)?.id) {
                _queueIndex.value = logical
                PlaybackBus.publishStation(q[logical])
            }

            // A huge queue is played as a window; when that window is nearly
            // spent, roll it forward in [_source] so the library never silently
            // stops at the boundary. Only fires for genuinely long sources, and
            // only once per boundary crossing (guarded), so it can never loop.
            if (oneToOne && _source.size > WINDOW && playerIndex >= c.mediaItemCount - 2) {
                val abs = windowBase + playerIndex
                val next = (abs + 1).coerceIn(0, _source.lastIndex)
                if (next > windowBase && next <= _source.lastIndex &&
                    (_extending == null || _extending!!.isCompleted)
                ) {
                    _extending?.cancel()
                    _extending = scope.launch {
                        delay(1500)
                        val c2 = controller ?: return@launch
                        if (c2.currentMediaItemIndex >= c2.mediaItemCount - 2 && next <= _source.lastIndex) {
                            slideWindow(c2, next)
                            sync()
                        }
                    }
                }
            }
        }

        val qi = _queueIndex.value
        val abs = windowBase + qi
        _state.value = PlayerState(
            playing = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            idle = c.playbackState == Player.STATE_IDLE && c.mediaItemCount == 0,
            positionMs = c.currentPosition.coerceAtLeast(0),
            bufferedMs = (c.bufferedPosition - c.currentPosition).coerceAtLeast(0),
            volume = c.volume,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
            seekable = c.isCurrentMediaItemSeekable,
            live = c.isCurrentMediaItemLive,
            hasPrev = abs > 0,
            hasNext = _source.size > 1 && abs < _source.lastIndex,
        )

        // Episode positions are written from here because this is the only
        // place that already holds both the station and the player's clock.
        // Throttled to [PROGRESS_INTERVAL]: sync runs twice a second, and an
        // episode does not need committing to disk twenty times a minute.
        val playingNow = PlaybackBus.station.value
        if (playingNow != null && playingNow.source == StationSource.Podcast && c.isPlaying) {
            val now = System.currentTimeMillis()
            val position = c.currentPosition
            if (position > 0 && now - lastProgressWrite >= PROGRESS_INTERVAL) {
                lastProgressWrite = now
                val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                    ?: playingNow.durationMs
                progressSink?.let { sink -> scope.launch { sink(playingNow, position, duration) } }
            }
        }
    }

    fun play(station: Station, from: List<Station> = emptyList(), preserveOrder: Boolean = false) {
        var q = _queue.value
        if (from.isNotEmpty()) {
            if (!preserveOrder) _baseSource = from
            // Cap the queued list to a bounded window around the tapped track so
            // a huge source (the whole local library) doesn't flood the queue.
            // The active order is linear, or shuffled if shuffle is on; the
            // tapped track keeps playing first, so it heads the shuffled list.
            // Navigation (step) passes preserveOrder so it rides the already
            // shuffled list instead of re-randomising - and restarting - on
            // every prev/next.
            val order = if (!preserveOrder && _shuffle.value && from.size > 1) shuffledKeepFirst(from, station) else from
            _source = order
            val srcIdxO = order.indexOfFirst { it.url == station.url }.coerceAtLeast(0)
            windowBase = if (order.size > WINDOW) srcIdxO else 0
            q = sliceAt(order, srcIdxO)
            _queue.value = q
            _queueIndex.value = (srcIdxO - windowBase).coerceIn(0, q.lastIndex.coerceAtLeast(0))
        } else if (q.none { it.url == station.url }) {
            _baseSource = listOf(station)
            _source = listOf(station)
            _queue.value = listOf(station)
            q = listOf(station)
            _queueIndex.value = 0
            windowBase = 0
        } else {
            _baseSource = q
            val order = if (_shuffle.value && q.size > 1) shuffledKeepFirst(q, station) else q
            _source = order
            _queueIndex.value = order.indexOfFirst { it.url == station.url }
            windowBase = 0
        }
        val queue = q
        val start = windowBase + _queueIndex.value.coerceAtLeast(0)

        PlaybackBus.publishStation(station)
        PlaybackBus.publishSource(_source)
        PlaybackBus.publishError(null)
        PlaybackBus.publishFormat(StreamFormat())

        // Persist what is playing so the widget, the quick tile and a fresh
        // launch all agree on the current station. This is the single play
        // choke point, so it covers app taps, the notification, the widget and
        // auto-advance - not just plays fired through the root's onPlay hook.
        val prefs = (context.applicationContext as CliampApp).prefs
        scope.launch {
            prefs.setLastStation(station)
            prefs.pushHistory(station)
            CliampWidgetReceiver.refresh(context.applicationContext)
        }

        // The tapped station is published and rendered first, on the calling
        // thread, so the music screen and mini bar update the instant a song is
        // touched. MediaController insists its methods run on the main thread,
        // but launching on the plain Main dispatcher (not `immediate`) yields
        // to the looper first, so that already-published new title and plate
        // draw a frame before the blocking setMediaItems/prepare work runs.
        _navJob?.cancel()
        val job = scope.launch(Dispatchers.Main) {
            val c = controller ?: return@launch
            // Any queue of finite tracks is a real playlist, so Media3 plays one
            // after another regardless of where they came from: local files and
            // provider albums alike. A live radio stream has no end to advance
            // from, so it is pushed as ONE Media3 item even though the queue
            // panel still shows the rest of its list as up-next to switch to.
            val allTracks = queue.all { it.isTrack } && queue.size > 1
            swapping = true
            try {
                ensureActive()
                if (allTracks) {
                    slideWindow(c, start)
                } else {
                    // Single live stream or lone track: Media3 holds just the
                    // tapped playable. The queue window was already computed
                    // above so the panel shows the neighbours, but the player
                    // advances nothing automatically. A part-listened episode
                    // still opens where it was left.
                    c.setMediaItems(listOf(buildItem(station)), 0, resumeAt(station))
                }
                ensureActive()
                c.prepare()
                c.play()
            } finally {
                swapping = false
            }
            ensureActive()
            sync()
        }
        _navJob = job
    }

    /**
     * Advances the currently-windowed Media3 playlist to [start] in [_source],
     * sliding [_queue] to the matching bounded window. For short sources (whole
     * list fits) it pushes everything from [start] onward (or the whole list if
     * [start] is the tapped index and the list is short).
     */
    private suspend fun slideWindow(c: Player, start: Int) {
        val src = _source
        if (src.isEmpty()) return
        val abs = start.coerceIn(0, src.lastIndex)
        val slice = sliceAt(src, abs)
        windowBase = if (src.size > WINDOW) abs else 0
        _queue.value = slice
        val index = (abs - windowBase).coerceIn(0, slice.lastIndex)
        _queueIndex.value = index
        val items = slice.map { buildItem(it) }
        val startAt = slice.getOrNull(index)?.let { resumeAt(it) } ?: 0L
        c.setMediaItems(items, index.coerceIn(0, items.lastIndex), startAt)
    }

    /**
     * Returns a bounded window into [source] beginning at [srcIndex], capped at
     * [WINDOW] tracks when the source is huge; short sources are returned whole.
     */
    private fun sliceAt(source: List<Station>, srcIndex: Int): List<Station> {
        if (source.size <= WINDOW) return source
        val start = srcIndex.coerceIn(0, source.lastIndex)
        val end = minOf(start + WINDOW, source.size)
        return source.subList(start, end)
    }

    /**
     * Returns a shuffled copy of [base] with [first] kept at the front, so the
     * currently- or tapped-playing item is not interrupted while the rest of
     * the list plays in random order. Works for tracks and stations alike.
     */
    private fun shuffledKeepFirst(base: List<Station>, first: Station): List<Station> {
        val rest = base.filter { it.url != first.url }
        return listOf(first) + rest.shuffled()
    }

    /**
     * Toggles shuffled playback of the current list. When switched on, the list
     * the user is playing (local songs, favourites, a provider album, a station
     * wall - whatever [_source] holds) is re-ordered so the order of the list is
     * shuffled, always keeping the current item first. Toggling off restores
     * the original linear order from [_baseSource]. On a lone item there is
     * nothing to reorder, so only the flag flips.
     */
    fun toggleShuffle() {
        // Pure-flip plus an in-place Media3 reorder. The model and the player
        // are reordered together so sync() never sees a mismatch (a mismatch is
        // what made a tap near the transport resolve to a different song).
        val newOn = !_shuffle.value
        _shuffle.value = newOn
        val c = controller
        if (newOn) {
            if (_source.size < 2) { sync(); return }
            val base = _baseSource.ifEmpty { _source }
            val current = _source.getOrNull(_queueIndex.value.takeIf { it >= 0 }?.let { windowBase + it } ?: 0)
                ?: _baseSource.firstOrNull()
                ?: _queue.value.firstOrNull()
                ?: _source.first()
            val abs = base.indexOfFirst { it.url == current.url }.coerceAtLeast(0)
            val rest = base.filterIndexed { i, s -> i != abs }.shuffled()
            val reordered = ArrayList<Station>(base.size)
            var ri = 0
            for (i in base.indices) {
                if (i == abs) reordered.add(current) else reordered.add(rest[ri++])
            }
            _shuffledSource = reordered
            _source = reordered
            // Keep the model and the Media3 so the poller's sync() never sees a
            // mismatch (that mismatch is what made a tap near the transport
            // resolve to a different song). moveMediaItem relocates items
            // without stopping or resetting the currently-playing one.
            if (c != null && c.mediaItemCount == reordered.size) {
                reorderPlayerItems(c, reordered.map { it.id })
            }
            windowBase = if (reordered.size > WINDOW) abs else 0
            _queue.value = sliceAt(reordered, abs)
            _queueIndex.value = (abs - windowBase).coerceIn(0, _queue.value.lastIndex.coerceAtLeast(0))
        } else {
            val linear = _baseSource.ifEmpty { _shuffledSource ?: _source }
            _shuffledSource = null
            val current = _source.getOrNull(
                (_queueIndex.value.takeIf { it >= 0 }?.let { windowBase + it } ?: 0),
            ) ?: linear.firstOrNull()
            _source = linear.ifEmpty { listOf(current).filterNotNull() }
            val abs = current?.let { linear.indexOfFirst { s -> s.url == it.url }.coerceAtLeast(0) } ?: 0
            if (c != null && c.mediaItemCount == linear.size && c.mediaItemCount == _source.size) {
                reorderPlayerItems(c, linear.map { it.id })
            }
            windowBase = if (_source.size > WINDOW) abs else 0
            _queue.value = sliceAt(_source, abs)
            _queueIndex.value = (abs - windowBase).coerceIn(0, _queue.value.lastIndex.coerceAtLeast(0))
        }
        sync()
    }

    /**
     * Rearranges the items already on [c] to match [order] (a list of Media3
     * mediaIds) without stopping or resetting playback. Items are relocated with
     * moveMediaItem; the currently-playing item is never relocated, so nothing
     * reloads and no setMediaItems / prepare is involved. This keeps Media3 and
     * the shuffled [_queue] aligned, so the poller's sync() cannot resolve a
     * different song after a shuffle toggle.
     */
    private fun reorderPlayerItems(c: Player, order: List<String>) {
        if (order.size != c.mediaItemCount) return
        val cur = c.currentMediaItemIndex.coerceIn(0, c.mediaItemCount - 1)
        val curId = c.getMediaItemAt(cur).mediaId
        val ids = ArrayList<String>(c.mediaItemCount)
        for (i in 0 until c.mediaItemCount) ids.add(c.getMediaItemAt(i).mediaId)
        var i = 0
        while (i < order.size) {
            if (ids[i] == order[i]) { i++; continue }
            val want = order[i]
            if (want == curId) { i++; continue } // never relocate the playing item
            var j = i + 1
            while (j < c.mediaItemCount && ids[j] != want) j++
            if (j >= c.mediaItemCount) { i++; continue }
            c.moveMediaItem(j, i)
            ids.add(i, ids.removeAt(j))
            i++
        }
    }

    /**
     * Resolves [station]'s stream URL and builds its Media3 item on a
     * background thread. Building an item renders the station's 512px artwork
     * (a first-time PNG encode, plus a synchronised cache read on every hit) -
     * enough to stall the main thread for every track in a queue window, which
     * is exactly the jank felt touching a song in a long list. The tapped
     * station is already published and composed synchronously when this runs,
     * so the tap leg only ever brings back the finished items.
     */
    /**
     * A part-listened episode opens where it was left. Radio and local files
     * have no saved position, so this is 0 for everything but podcasts and the
     * lookup is skipped entirely for them.
     */
    private suspend fun resumeAt(station: Station): Long =
        if (station.source != StationSource.Podcast) 0L
        else resumeLookup?.invoke(station) ?: 0L

    private suspend fun buildItem(station: Station): MediaItem =
        withContext(Dispatchers.Default) {
            PlaybackService.mediaItem(context, station, StreamResolver.resolve(station.url))
        }

    fun toggle(fallback: Station? = null) {
        val c = controller ?: return
        if (c.isPlaying) c.pause()
        else {
            if (c.mediaItemCount == 0) {
                // Nothing loaded this session. Prefer the live bus station, but
                // accept a caller-supplied fallback (e.g. the last-played station
                // shown from history) so the transport can start playback even
                // before anything has been tuned.
                (PlaybackBus.station.value ?: fallback)?.let { play(it) }
            } else {
                // a stalled live stream has to be re-primed, not resumed
                c.prepare()
                c.play()
            }
        }
        sync()
    }

    fun stop() {
        controller?.stop()
        controller?.clearMediaItems()
        PlaybackBus.publishStreamTitle("")
        sync()
    }

    fun next() = step(+1)
    fun prev() = step(-1)

    /**
     * Moves prev/next by [delta]. Each tap targets exactly one song forward or
     * back from the currently committed track. Rapid taps coalesce on a single
     * pending absolute target so parallel Media3 re-queues can't race each other
     * and skip past the song you actually tapped to.
     */
    private fun step(delta: Int) {
        val src = _source.ifEmpty { _fallbackSource }
        if (src.isEmpty()) return
        // Base prev/next on the latest pending target (if any) rather than the
        // committed index, so rapid taps stack onto one another instead of all
        // collapsing onto the same song. Either way the value is an absolute
        // index into [_source].
        val pending = _navPending
        val here = pending ?: (windowBase + _queueIndex.value)
        val abs = if (_source.isEmpty() && pending == null) {
            val shown = PlaybackBus.station.value?.let { s ->
                src.indexOfFirst { it.url == s.url }
            } ?: -1
            (if (shown >= 0) shown + delta else 0).coerceIn(0, src.lastIndex)
        } else {
            (here + delta).coerceIn(0, src.lastIndex)
        }
        if (abs == here && _source.isNotEmpty() && pending == null) return

        val now = android.os.SystemClock.uptimeMillis()
        val leadingEdge = now - _lastNavTapMs > NAV_DEBOUNCE_MS
        _lastNavTapMs = now
        _navPending = abs
        if (leadingEdge) {
            // Isolated tap: navigate at once so next / prev feel instant.
            _navTimerJob?.cancel()
            _navTimerJob = null
            applyNavigation()
        } else {
            // Rapid burst: wait out the taps, then land on the final one.
            startNavTimer()
        }
    }

    /**
     * Fires the pending target now (no timer). When the target is already one
     * of the items loaded in Media3's current window we switch to it in place
     * with seekTo, which picks an already-prepared item - no setMediaItems, no
     * re-prepare, no decode gap - so prev / next are effectively instant. Only
     * when the target lies outside the window (or nothing is loaded) do we fall
     * back to [play], which rebuilds the queue.
     */
    private fun applyNavigation() {
        val target = _navPending ?: return
        _navPending = null
        val src = _source.ifEmpty { _fallbackSource }
        if (src.isEmpty()) return
        val abs = target.coerceIn(0, src.lastIndex)
        val station = src[abs]

        // Publish and persist the target exactly as play() would, so the UI and
        // the widget agree the instant the song is tapped - even before Media3
        // has switched over.
        PlaybackBus.publishStation(station)
        PlaybackBus.publishSource(src)
        PlaybackBus.publishError(null)
        PlaybackBus.publishFormat(StreamFormat())
        val prefs = (context.applicationContext as CliampApp).prefs
        scope.launch {
            prefs.setLastStation(station)
            prefs.pushHistory(station)
            CliampWidgetReceiver.refresh(context.applicationContext)
        }

        val q = _queue.value
        val c = controller
        val inWindow = c != null && q.isNotEmpty() && abs >= windowBase && abs < windowBase + q.size
        if (inWindow) {
            val windowIndex = (abs - windowBase).coerceIn(0, q.lastIndex)
            _queueIndex.value = windowIndex
            _navJob?.cancel()
            val job = scope.launch(Dispatchers.Main) {
                val player = controller ?: return@launch
                swapping = true
                try {
                    ensureActive()
                    val resume = resumeAt(station)
                    ensureActive()
                    player.seekTo(windowIndex.coerceIn(0, player.mediaItemCount - 1), resume)
                    player.play()
                } finally {
                    swapping = false
                }
                ensureActive()
                sync()
            }
            _navJob = job
        } else {
            play(station, src, preserveOrder = true)
        }
    }

    /**
     * Restarts the coalescing timer. When it fires (taps have settled) we
     * navigate ONCE to the last tapped target. The timer only waits and hands
     * off to play(); it never cancels an in-flight media job.
     */
    private fun startNavTimer() {
        _navTimerJob?.cancel()
        _navTimerJob = scope.launch(Dispatchers.Main) {
            try {
                delay(NAV_DEBOUNCE_MS)
            } catch (_: CancellationException) {
                return@launch
            }
            if (_navTimerJob === coroutineContext[Job]) _navTimerJob = null
            applyNavigation()
        }
    }

    /**
     * Push the current [_queue] to the player so edits (add / remove / reorder)
     * actually change what plays next, not just what the panel shows. After a
     * manual edit the arranged [_queue] IS the navigable source, so [_source] is
     * collapsed to it too: prev / next keep walking the list the user built.
     */
    private fun applyQueueToPlayer() {
        val q = _queue.value
        if (q.isEmpty()) return
        val idx = _queueIndex.value.coerceIn(0, q.lastIndex)
        _baseSource = q
        _source = q
        windowBase = 0
        _queueIndex.value = idx
        scope.launch(Dispatchers.Main) {
            val c = controller ?: return@launch
            if (q.size > 1 && q.all { it.isTrack }) {
                val items = q.map { buildItem(it) }
                c.setMediaItems(items, idx.coerceIn(0, items.lastIndex), 0L)
            }
            sync()
        }
    }

    /** Insert [station] into the queue without replacing it. */
    fun addToQueue(station: Station, at: Int = Int.MAX_VALUE) {
        val q = _queue.value.toMutableList()
        val qi = _queueIndex.value
        val pos = if (at == Int.MAX_VALUE) q.size else at.coerceIn(0, q.size)
        val insertBefore = pos <= qi
        q.add(pos, station)
        _queue.value = q
        if (insertBefore) _queueIndex.value = qi + 1
        applyQueueToPlayer()
    }

    /** Insert [station] right after the currently-playing item (play next). */
    fun playNext(station: Station) {
        val q = _queue.value.toMutableList()
        val qi = _queueIndex.value
        val insertAt = if (qi in q.indices) qi + 1 else q.size
        q.add(insertAt, station)
        _queue.value = q
        applyQueueToPlayer()
    }

    /** Play [station] with [from] as a brand-new queue, replacing whatever was queued. */
    fun replaceQueue(station: Station, from: List<Station>) {
        play(station, from)
    }

    /** Drop the station at [index], keeping the playing item stable. */
    fun removeFromQueue(index: Int) {
        val q = _queue.value
        if (index !in q.indices) return
        val qi = _queueIndex.value
        val newQ = q.filterIndexed { i, _ -> i != index }
        _queue.value = newQ
        _queueIndex.value = when {
            index < qi -> (qi - 1).coerceAtLeast(-1)
            index == qi && newQ.isEmpty() -> -1
            index == qi -> qi
            else -> qi
        }
        applyQueueToPlayer()
    }

    /** Move the station at [from] to [to], keeping the playing item stable. */
    fun reorderQueue(from: Int, to: Int) {
        val q = _queue.value
        if (from !in q.indices || to !in q.indices || from == to) return
        val qi = _queueIndex.value
        val item = q[from]
        val moved = q.toMutableList().apply {
            removeAt(from)
            add(to, item)
        }
        _queue.value = moved
        // the playing station follows its item through the move
        _queueIndex.value = moved.indexOfFirst { it.url == item.url }.let { if (qi == from) it else qi }
        applyQueueToPlayer()
    }

    fun clearQueue() {
        if (_queue.value.isEmpty()) return
        _queue.value = emptyList()
        _queueIndex.value = -1
        _baseSource = emptyList()
        _shuffledSource = null
        _source = emptyList()
        sync()
    }

    /**
     * Seeks by fraction rather than milliseconds so the caller does not need to
     * know the duration, and so a drag on a 3 minute track and a 3 hour one
     * behave the same.
     */
    fun seekTo(fraction: Float) {
        val c = controller ?: return
        if (!c.isCurrentMediaItemSeekable) return
        val d = c.duration
        if (d == C.TIME_UNSET || d <= 0) return
        // Seeking to the very end of a playlist item makes Media3 immediately
        // auto-advance to the next track, which a user reading a seek on the far
        // edge of the bar experiences as "just changed the song". Clamp the
        // target just short of the tail so the seek stays on the current item.
        val target = d * fraction.coerceIn(0f, 1f)
        if (target >= d - 250) return
        c.seekTo(target.toLong())
        sync()
    }

    fun setVolume(v: Float) {
        controller?.volume = v.coerceIn(0f, 1f)
        sync()
    }

    fun release() {
        controller?.release()
        controller = null
    }

    @Suppress("unused")
    private fun currentItem(): MediaItem? = controller?.currentMediaItem
}

data class PlayerState(
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val idle: Boolean = true,
    val positionMs: Long = 0,
    val bufferedMs: Long = 0,
    val volume: Float = 1f,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
    /** 0 when the source has no known length, which is the live-stream case. */
    val durationMs: Long = 0L,
    val seekable: Boolean = false,
    val live: Boolean = false,
) {
    /** A scrubber is only honest when there is a length to scrub through. */
    val scrubbable: Boolean get() = seekable && !live && durationMs > 0
}
