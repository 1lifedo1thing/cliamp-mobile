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
import stream.cliamp.mobile.data.wrapNext
import stream.cliamp.mobile.widget.WidgetRenderer

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

    /**
     * While navigation is walking the launch-seeded fallback (nothing has been
     * played from a real list this session), prev/next run it as a ring - the
     * same wrap the widget uses - so both keys always have somewhere to go from
     * the restored song. Cleared the moment the user plays from an actual list.
     */
    private var _ringFallback = false

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
            prefs.resumeLocal.collect { _resumeLocal.value = it }
        }
    }

    /** Whether shuffled playback is switched on. */
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    /** Whether local files resume (podcasts and provider tracks always do). */
    private val _resumeLocal = MutableStateFlow(false)


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

    // Shuffle's Media3 rebuild deliberately lives off [_navJob]. A toggle
    // followed immediately by prev/next (or an auto-advance) cancels [_navJob]
    // to stop navigation racing a stale rebuild; routing the shuffle rebuild
    // through [_navJob] meant that same cancel killed the shuffle window before
    // it was applied, snapping playback back to the linear queue. This job is
    // only cancelled by a new explicit play or a new toggle, so a shuffle
    // always lands.
    private var _shuffleJob: Job? = null

    private val NAV_DEBOUNCE_MS = 180L

    /** Queues larger than this are played lazily from a window, not in full. */
    private val WINDOW = 60

    /** How often a playing episode's position reaches the database. */
    private val PROGRESS_INTERVAL = 5_000L

    /** The list prev/next walks. Set whenever the user plays from a list. */
    val queue: StateFlow<List<Station>> = _queue.asStateFlow()

    /**
     * The station behind a Media3 item id (item ids are station ids), or null.
     * Lets observers that fire mid-transition - before the published station
     * has caught up - resolve what is actually audible right now instead of
     * reading a stale bus value.
     */
    fun stationForMediaId(id: String): Station? =
        _queue.value.firstOrNull { it.id == id } ?: _source.firstOrNull { it.id == id }

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
            val curId = c.getMediaItemAt(playerIndex).mediaId
            // Resolve which queued station is actually audible. For a real
            // playlist [_queue] must mirror Media3's window exactly, so the
            // currently-playing item is at [_queue][playerIndex] - by id, not by
            // a stored index. A raw stored index is what caused "shows one song
            // while playing another": when a near-tail roll shrank [_queue] to
            // the source tail while Media3 kept the old wider window (or shuffle
            // drifted the two), [_queueIndex] and Media3's index no longer named
            // the same item. Rebuilding [_queue] from Media3's actual window
            // keeps the display honest in every case. A radio / lone-track item
            // (Media3 count == 1) deliberately plays a full panel window around
            // a single Media3 item, so we find it by id instead of collapsing.
            val resolvedIndex: Int? = if (oneToOne &&
                q.indexOfFirst { it.id == curId } == playerIndex
            ) {
                playerIndex
            } else if (c.mediaItemCount == 1) {
                q.indexOfFirst { it.id == curId }.let { if (it >= 0) it else null }
            } else if (mirrorQueueFromMedia3(c)) {
                playerIndex
            } else {
                null
            }
            // Publish whenever the resolved item is genuinely different from what
            // the bus already shows - by ID, not by queue-index. When the window
            // had to be re-mirrored the audible song lands back at [playerIndex],
            // so comparing indices would see "no change" and freeze the title on
            // a stale song while its shuffle / roll actually advanced; comparing
            // the resolved id against the published bus station keeps the title
            // honest even through a re-mirror.
            if (resolvedIndex != null) {
                val resolved = q.getOrNull(resolvedIndex)
                if (resolved != null && resolved.id != PlaybackBus.station.value?.id) {
                    _queueIndex.value = resolvedIndex
                    PlaybackBus.publishStation(resolved)
                }
            }

            // A huge queue is played as a window; when that window is nearly
            // spent, roll it forward in [_source] so the library never silently
            // stops at the boundary. Only fires for genuinely long sources, and
            // only once per boundary crossing (guarded), so it can never loop.
            if (oneToOne && _source.size > WINDOW && _queueIndex.value >= 0 &&
                _queueIndex.value >= c.mediaItemCount - 2
            ) {
                // Base the roll target on the live model position so a stale
                // next can't rewind the queue to a window the audio has left.
                val modelAbs = windowBase + _queueIndex.value
                val candidate = (modelAbs + 1).coerceIn(0, _source.lastIndex)
                if (candidate > windowBase && candidate <= _source.lastIndex &&
                    (_extending == null || _extending!!.isCompleted)
                ) {
                    _extending?.cancel()
                    _extending = scope.launch {
                        delay(1500)
                        val c2 = controller ?: return@launch
                        // Recompute the target from live state so a roll can never
                        // rewind the queue to a window the audio has gone past;
                        // only roll while playback is actually spent near the end
                        // of the current window.
                        val pi = c2.currentMediaItemIndex
                        if (pi >= c2.mediaItemCount - 2 && _queueIndex.value >= 0) {
                            val abs = windowBase + _queueIndex.value
                            val next = (abs + 1).coerceIn(0, _source.lastIndex)
                            if (next > windowBase && next <= _source.lastIndex) {
                                slideWindow(c2, next)
                                sync()
                            }
                        }
                    }
                }
            }
        }

        val qi = _queueIndex.value
        val abs = windowBase + qi
        // Prev/next navigate whichever list [step] walks: the live [_source]
        // after anything has played, else the seeded fallback (recent history /
        // favourites) so the buttons work from the song shown at launch, before
        // anything has actually played this session.
        val nav = _source.ifEmpty { _fallbackSource }
        val ring = nav.size > 1 && (_source.isEmpty() || _ringFallback)
        val navIdx = if (ring) -1
        else if (_source.isNotEmpty() || _queueIndex.value >= 0) abs
        else (PlaybackBus.station.value ?: nav.firstOrNull())?.let { s ->
            nav.indexOfFirst { it.url == s.url }
        } ?: -1
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
            hasPrev = if (ring) true else nav.isNotEmpty() && navIdx > 0,
            hasNext = if (ring) true else nav.size > 1 && navIdx in 0 until nav.lastIndex,
        )

        // Track positions are written from here because this is the only
        // place that already holds both the station and the player's clock.
        // Throttled to [PROGRESS_INTERVAL]: sync runs twice a second, and a
        // track does not need committing to disk twenty times a minute.
        val playingNow = PlaybackBus.station.value
        if (playingNow != null && playingNow.isTrack && c.isPlaying &&
            (playingNow.source != StationSource.Local || _resumeLocal.value)
        ) {
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

    /**
     * Rebuilds the [_queue] window mirror to match exactly what Media3 is
     * actually holding, item by item, keyed on media id. Used when [_queue] and
     * Media3's window have drifted out of phase - a near-tail roll that shrank
     * [_queue] to the source tail while Media3 kept the old wider window, or a
     * shuffle realign that moved only [_queue] - so the display always names the
     * same songs Media3 is playing, in the same order. Returns false when Media3
     * holds no items we can map back to [_source] (a live stream we can't anchor).
     */
    private fun mirrorQueueFromMedia3(c: Player): Boolean {
        val src = _source
        if (src.isEmpty()) return false
        val count = c.mediaItemCount
        if (count == 0) return false
        val pi = c.currentMediaItemIndex.coerceIn(0, count - 1)
        val audibleId = c.getMediaItemAt(pi).mediaId
        val items = ArrayList<Station>(count)
        var playerIdx = -1
        for (i in 0 until count) {
            val s = src.firstOrNull { it.id == c.getMediaItemAt(i).mediaId } ?: continue
            items.add(s)
            if (s.id == audibleId) playerIdx = items.lastIndex
        }
        if (items.isEmpty() || playerIdx < 0) return false
        val first = items.first()
        windowBase = src.indexOfFirst { it.id == first.id }.coerceAtLeast(0)
        _queue.value = items
        _queueIndex.value = playerIdx
        return true
    }

    /**
     * Persist a bounded window of the list being played, centred on [station],
     * so the widget's prev/next can walk the current domain (local / radio /
     * podcast) without depending on the in-memory PlaybackBus, which is empty
     * when the widget wakes a cold process. Kept small because the whole source
     * (e.g. the full local library) is far too large to serialize per play.
     *
     * The up-next four are written in the same write so the widget's "next"
     * row updates in lockstep with its title the instant a song changes, rather
     * than waiting on the service's next player event.
     */
    /** Persist the now-current station and push it to the widget, as a single choke point. */
    private fun persistAndRefresh(station: Station) {
        val prefs = (context.applicationContext as CliampApp).prefs
        // A fresh play means intent-to-play; the service confirms audibility
        // through its own events. The push carries the in-memory row so the
        // widget never waits on the persistence write beside it.
        WidgetRenderer.push(
            context.applicationContext,
            station,
            PlaybackBus.streamTitle.value,
            true,
            seekable = station.isTrack,
            durationMs = station.durationMs,
            positionMs = 0L,
        )
        scope.launch {
            prefs.setLastStation(station)
            prefs.pushHistory(station)
        }
    }

    private fun persistWidgetWindow(station: Station) {
        val src = _source
        if (src.isEmpty()) return
        val prefs = (context.applicationContext as CliampApp).prefs
        val i = src.indexOfFirst { it.url == station.url }
        if (i < 0) return
        val before = 8
        val after = 8
        val n = src.size
        // Wrap both sides of the window with a positive modulo. Kotlin's `%`
        // keeps the dividend's sign, so `(i + k) % n` is NEGATIVE when k is
        // bigger than i - and src[negative] throws an IndexOutOfBoundsException
        // (hitting a song inside a short search result list crashed: a 6-item
        // source, i=0, k=-8 -> src[-2]). The +n % n floors it into range, and
        // the source is a radio-style loop anyway: the widget's prev/next walk
        // it as a ring.
        fun wrap(k: Int) = ((i + k) % n + n) % n
        val win = mutableListOf<Station>()
        for (k in -before..after) win.add(src[wrap(k)])
        val next = src.wrapNext(i)
        scope.launch {
            prefs.setWidgetSource(win)
            prefs.setWidgetNext(next)
        }
    }

    fun play(station: Station, from: List<Station> = emptyList(), preserveOrder: Boolean = false) {
        var q = _queue.value
        if (from.isNotEmpty()) {
            // A play tapped on a real screen hands navigation over to that list
            // (linear, not the launch fallback ring).
            if (!preserveOrder) _ringFallback = false
            // Cap the queued list to a bounded window around the tapped track so
            // a huge source (the whole local library) doesn't flood the queue.
            // The active order is linear, or shuffled if shuffle is on; the
            // tapped track keeps playing first, so it heads the shuffled list.
            // Navigation (step) passes preserveOrder so it rides the already
            // shuffled list instead of re-randomising - and restarting - on
            // every prev/next.
            val order = if (!preserveOrder && _shuffle.value && from.size > 1) shuffledKeepFirst(from, station) else from
            // A fresh play from a list rebases the shuffle bookkeeping on that
            // list's own order - already the user's chosen sort from the screen
            // the tap came from. Without this rebase a stale _baseSource from an
            // earlier play survives, so a later shuffle toggle rebuilds - and
            // toggling off restores - the *previous* list instead of this one.
            // Navigation (preserveOrder) rides the already-active order, so it
            // must leave that bookkeeping untouched.
            if (!preserveOrder) {
                _baseSource = from
            }
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
        android.util.Log.d("cliamp/wid", "PLAY source.size=${_source.size} station=${station.name} preserve=$preserveOrder")
        persistWidgetWindow(station)
        PlaybackBus.publishError(null)
        PlaybackBus.publishFormat(StreamFormat())

        // Persist what is playing so the widget, the quick tile and a fresh
        // launch all agree on the current station. This is the single play
        // choke point, so it covers app taps, the notification, the widget and
        // auto-advance - not just plays fired through the root's onPlay hook.
        persistAndRefresh(station)

        // The tapped station is published and rendered first, on the calling
        // thread, so the music screen and mini bar update the instant a song is
        // touched. MediaController insists its methods run on the main thread,
        // but launching on the plain Main dispatcher (not `immediate`) yields
        // to the looper first, so that already-published new title and plate
        // draw a frame before the blocking setMediaItems/prepare work runs.
        _navJob?.cancel()
        _shuffleJob?.cancel()
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
                    // If Media3 already holds exactly the window the model now
                    // expects (same ids, same order - the common case of tapping a
                    // song inside a source that is already loaded), switch in place
                    // by index with seekTo instead of tearing the whole window down
                    // and rebuilding it. A full setMediaItems keeps the previous
                    // song audible for a second or two while every item in the new
                    // window is re-resolved and re-published; seekTo is instant.
                    val cq = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
                    val wq = queue.map { it.id }
                    val alreadyLoaded = cq.size == wq.size && wq.indices.all { cq[it] == wq[it] }
                    if (alreadyLoaded) {
                        val target = _queueIndex.value.coerceIn(0, c.mediaItemCount - 1)
                        val resume = queue.getOrNull(target)?.let { resumeAt(it) } ?: 0L
                        c.seekTo(target, resume)
                    } else {
                        slideWindow(c, start)
                    }
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
        // Raised around the swap so the half-second poller's sync() can't run
        // between [_queue] being repointed at the new window and Media3 actually
        // switching. Un-guarded, that interleaving resolves the still-playing
        // Media3 item against the fresh [_queue], falls back to the raw index and
        // publishes a station that is not what is audible - "shows one song while
        // playing another". Once Media3 is set, [_queue] and its index are coherent
        // so the consumer side of sync() is safe to resume.
        swapping = true
        try {
            // Tracks ride a real multi-item window so they auto-advance and
            // seek in place. Anything else (live radio, mixed walls) loads
            // exactly the audible item - same as play()'s single-item path -
            // so a next/prev never resolves dozens of streams and artworks it
            // will never play. The [_queue] mirror above still holds the whole
            // window, so the panel, the widget ring and the anchors keep
            // walking the full list either way.
            if (slice.all { it.isTrack } && slice.size > 1) {
                val items = slice.map { buildItem(it) }
                val startAt = slice.getOrNull(index)?.let { resumeAt(it) } ?: 0L
                c.setMediaItems(items, index.coerceIn(0, items.lastIndex), startAt)
            } else {
                val target = slice.getOrNull(index) ?: return
                c.setMediaItems(listOf(buildItem(target)), 0, resumeAt(target))
            }
        } finally {
            swapping = false
        }
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
        // Model reorder plus a seamless Media3 head/tail swap. Both the model
        // and the player end up on the new order so sync() never sees a
        // mismatch (a mismatch is what made a cardboard shuffle either only
        // reach the loaded window - "a few random songs" - or show one song
        // while playing another) - but the audible item itself is never
        // touched, so there is no rebuffer or seek either.
        val newOn = !_shuffle.value
        val c = controller
        if (c == null || _source.isEmpty()) { _shuffle.value = newOn; sync(); return }
        val base = _baseSource.ifEmpty { _source }

        // Anchor "current" on Media3's LIVE audible item, not the model's
        // [_queueIndex]. sync() reconciles the model to the player on a slow
        // poll, so between a track auto-advancing (a real playlist of local
        // songs) and the next sync() the model can lag what is genuinely
        // audible. Rebuilding off a stale model index applies the running
        // position across to the wrong song, then re-anchors Media3 around it -
        // and since both model and player now agree on that wrong song, the
        // error persists even after toggling off again. The live Media3 item is
        // the only anchor that cannot drift.
        val audibleId = if (c.mediaItemCount > 0) c.getMediaItemAt(
            c.currentMediaItemIndex.coerceIn(0, c.mediaItemCount - 1)
        ).mediaId else null
        val current = _source.firstOrNull { it.id == audibleId }
            ?: _source.getOrNull(_queueIndex.value.takeIf { it >= 0 }?.let { windowBase + it } ?: 0)
            ?: _baseSource.firstOrNull()
            ?: _queue.value.firstOrNull()
            ?: _source.first()
        _shuffle.value = newOn
        if (newOn) {
            if (_source.size < 2) { sync(); return }
            val abs = base.indexOfFirst { it.url == current.url }.coerceAtLeast(0)
            val rest = base.filterIndexed { i, s -> i != abs }.shuffled()
            val reordered = ArrayList<Station>(base.size)
            var ri = 0
            for (i in base.indices) {
                if (i == abs) reordered.add(current) else reordered.add(rest[ri++])
            }
            _baseSource = base
            _source = reordered
        } else {
            _baseSource = base
            _source = base
        }

        // Re-anchor the model immediately so the panel shows the new order
        // instantly - the same reason the stations path feels smooth. The
        // Media3 tail is then swapped around the still-playing item below,
        // without touching it, so there is no rebuffer or seek.
        _extending?.cancel()
        _extending = null
        _shuffleJob?.cancel()
        _shuffleJob = null
        val src = _source
        val absJ = src.indexOfFirst { it.url == current.url }.coerceAtLeast(0)
        windowBase = if (src.size > WINDOW) absJ else 0
        val slice = sliceAt(src, absJ)
        _queue.value = slice
        val idx = (absJ - windowBase).coerceIn(0, slice.lastIndex.coerceAtLeast(0))
        _queueIndex.value = idx
        if (!(slice.all { it.isTrack } && slice.size > 1)) {
            // Anything else keeps the single-item shape play() gave it, and
            // that item is the current station itself: reordering the model
            // IS the shuffle, and swapping Media3 would only rebuffer the
            // same stream (plus resolve dozens of stations for nothing).
            sync()
            return
        }
        // Track playlist: keep the audible item playing exactly where it is
        // and only swap the items around it - the stations equivalent of
        // "reordering the model IS the shuffle". The old code rebuilt the
        // whole Media3 queue with setMediaItems(..., pos), which threw away
        // the current decoder and reloaded the same song from `pos`: an
        // audible gap plus up to WINDOW item resolves on the main thread.
        // Removing/adding only head and tail leaves the current item (and
        // its position) untouched, so toggling shuffle never interrupts.
        swapping = true
        val currentId = current.id
        _shuffleJob = scope.launch(Dispatchers.Main) {
            try {
                ensureActive()
                var anchorId = currentId
                var anchorSlice = slice
                var anchorIdx = idx
                // Build neighbours off the main thread: resolving + artwork
                // per item is what made the old toggle jank on long lists.
                var headItems = withContext(Dispatchers.Default) {
                    anchorSlice.subList(0, anchorIdx).map { buildItem(it) }
                }
                ensureActive()
                var tailItems = withContext(Dispatchers.Default) {
                    anchorSlice.subList(anchorIdx + 1, anchorSlice.size).map { buildItem(it) }
                }
                ensureActive()
                var p = controller ?: return@launch
                if (p.mediaItemCount == 0) {
                    val all = withContext(Dispatchers.Default) {
                        anchorSlice.map { buildItem(it) }
                    }
                    ensureActive()
                    p.setMediaItems(all, anchorIdx.coerceIn(0, all.lastIndex), 0L)
                    ensureActive()
                    sync()
                    return@launch
                }
                // The track may have rolled forward while the tail was
                // building; re-anchor on what is actually audible rather than
                // swapping a stale tail around the wrong song.
                var pi = p.currentMediaItemIndex.coerceIn(0, p.mediaItemCount - 1)
                var audibleNow = p.getMediaItemAt(pi).mediaId
                if (audibleNow != anchorId) {
                    val retry = _source.firstOrNull { it.id == audibleNow }
                    if (retry != null) {
                        anchorId = retry.id
                        val abs2 = _source.indexOfFirst { it.url == retry.url }.coerceAtLeast(0)
                        windowBase = if (_source.size > WINDOW) abs2 else 0
                        anchorSlice = sliceAt(_source, abs2)
                        _queue.value = anchorSlice
                        anchorIdx = (abs2 - windowBase).coerceIn(0, anchorSlice.lastIndex.coerceAtLeast(0))
                        _queueIndex.value = anchorIdx
                        headItems = withContext(Dispatchers.Default) {
                            anchorSlice.subList(0, anchorIdx).map { buildItem(it) }
                        }
                        ensureActive()
                        tailItems = withContext(Dispatchers.Default) {
                            anchorSlice.subList(anchorIdx + 1, anchorSlice.size).map { buildItem(it) }
                        }
                        ensureActive()
                        p = controller ?: return@launch
                        if (p.mediaItemCount == 0) {
                            val all = withContext(Dispatchers.Default) {
                                anchorSlice.map { buildItem(it) }
                            }
                            ensureActive()
                            p.setMediaItems(all, anchorIdx.coerceIn(0, all.lastIndex), p.currentPosition.coerceAtLeast(0))
                            ensureActive()
                            sync()
                            return@launch
                        }
                        pi = p.currentMediaItemIndex.coerceIn(0, p.mediaItemCount - 1)
                        audibleNow = p.getMediaItemAt(pi).mediaId
                        if (audibleNow != anchorId) return@launch
                    } else {
                        return@launch
                    }
                }
                // Tail first: the current index is unaffected, so `pi` stays
                // valid for the head swap that follows.
                val tailCount = p.mediaItemCount
                if (pi + 1 < tailCount || tailItems.isNotEmpty()) {
                    ensureActive()
                    if (pi + 1 < tailCount) p.removeMediaItems(pi + 1, tailCount)
                    ensureActive()
                    if (tailItems.isNotEmpty()) p.addMediaItems(pi + 1, tailItems)
                }
                ensureActive()
                // Head second: removing shifts the current item to 0, adding
                // the new head slides it to its shuffled index - playback of
                // the untouched current item continues throughout.
                if (pi > 0 || headItems.isNotEmpty()) {
                    ensureActive()
                    if (pi > 0) p.removeMediaItems(0, pi)
                    ensureActive()
                    if (headItems.isNotEmpty()) p.addMediaItems(0, headItems)
                }
                ensureActive()
                sync()
            } finally {
                if (_shuffleJob === coroutineContext[Job]) swapping = false
                else if (_shuffleJob == null) swapping = false
            }
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
    private suspend fun resumeAt(station: Station): Long {
        if (!station.isTrack) return 0L
        // Local files only resume when the user asked them to; podcasts and
        // provider tracks always do.
        if (station.source == StationSource.Local && !_resumeLocal.value) return 0L
        return resumeLookup?.invoke(station) ?: 0L
    }

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
        // The widget mirrors tap intent, not audibility: read playWhenReady
        // straight off the controller instead of waiting on the service's
        // next player event.
        WidgetRenderer.push(
            context.applicationContext,
            PlaybackBus.station.value,
            PlaybackBus.streamTitle.value,
            c.playWhenReady && c.mediaItemCount > 0,
        )
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
        // Anchor "here" on Media3's live position (windowBase + current index)
        // rather than the mutable [_queueIndex]. sync()/realign rewrite
        // [_queueIndex] under rolls and could otherwise pin the target to the
        // same song on every tap; the live index is strictly monotonic, so
        // prev/next always advance. Single-item playback is the exception:
        // live radio holds one Media3 item while the panel shows a whole
        // list, so the live index is always 0 - which pinned every next to
        // the same second song and swallowed every prev. There the published
        // station is the honest anchor.
        val liveHere = controller?.let { c ->
            if (c.mediaItemCount > 1) windowBase + c.currentMediaItemIndex else null
        }
        val busHere = PlaybackBus.station.value?.let { s ->
            src.indexOfFirst { it.url == s.url }.takeIf { it >= 0 }
        }
        val here = pending ?: liveHere ?: busHere ?: (windowBase + _queueIndex.value)
        val wrap = { k: Int -> ((k % src.size) + src.size) % src.size }
        val abs = if (_source.isEmpty() && pending == null) {
            val shown = (PlaybackBus.station.value ?: src.firstOrNull())?.let { s ->
                src.indexOfFirst { it.url == s.url }
            } ?: -1
            // Launch fallback: walk history as a ring so the first prev/next
            // from the restored song have somewhere to go.
            _ringFallback = true
            if (shown >= 0) wrap(shown + delta) else (0 + delta).coerceIn(0, src.lastIndex)
        } else if (_source.isEmpty() || _ringFallback) {
            _ringFallback = true
            wrap(here + delta)
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
        // A manual seek and the auto window-roll both rewrite Media3; if the
        // delayed roll lands on top of a user's seekTo it re-reads the seek's
        // index as a position in the freshly-rolled window and snaps playback
        // to the wrong (repeated) song - the "won't advance / plays same song
        // again" stall. Cancel any pending roll so a manual next/prev wins.
        _extending?.cancel()
        _extending = null
        val src = _source.ifEmpty { _fallbackSource }
        if (src.isEmpty()) return
        val abs = if (_ringFallback && src.size > 1) ((target % src.size) + src.size) % src.size
        else target.coerceIn(0, src.lastIndex)
        val station = src[abs]

        // Publish and persist the target exactly as play() would, so the UI and
        // the widget agree the instant the song is tapped - even before Media3
        // has switched over.
        PlaybackBus.publishStation(station)
        PlaybackBus.publishSource(src)
        PlaybackBus.publishError(null)
        PlaybackBus.publishFormat(StreamFormat())
        persistWidgetWindow(station)
        persistAndRefresh(station)

        val q = _queue.value
        val c = controller
        val inWindow = c != null && q.isNotEmpty() && abs >= windowBase && abs < windowBase + q.size
        if (inWindow) {
            val windowIndex = (abs - windowBase)
            _navJob?.cancel()
            val job = scope.launch(Dispatchers.Main) {
                val player = controller ?: return@launch
                // Only seek in place when the target is actually loaded by Media3;
                // a bare seekTo clamps to the last loaded item when the index is
                // out of range, silently freezing playback on that song. Otherwise
                // slide the window so the tapped song becomes the audible item.
                if (windowIndex < player.mediaItemCount) {
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
                    _queueIndex.value = windowIndex
                } else {
                    slideWindow(player, abs)
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
        _ringFallback = false
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
