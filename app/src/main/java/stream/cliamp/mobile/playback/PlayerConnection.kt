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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource

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

    /**
     * The linear list a play originated from, before any shuffle reordering.
     * Shuffle reorders a copy of it into [_source]; turning shuffle off restores
     * [_source] to this so prev / next walk the natural playlist order again.
     */
    private var _baseSource: List<Station> = emptyList()

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

    /** Queues larger than this are played lazily from a window, not in full. */
    private val WINDOW = 60

    /** The list prev/next walks. Set whenever the user plays from a list. */
    val queue: StateFlow<List<Station>> = _queue.asStateFlow()

    /** Index of the currently-playing station in [queue], or -1. */
    val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    val currentQueue: List<Station> get() = _queue.value

    /** Called once the controller is live, if the user asked for auto-resume. */
    var onReady: (() -> Unit)? = null

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

        // When Media3 advances a playlist it does so internally, so the index
        // has to be read back or the screen keeps showing the previous track.
        // Media3 always holds exactly [_queue] (a bounded window for a huge
        // source, or the whole list for a short one), so its window index maps
        // straight onto [_queue]; no source offset needed.
        val q = _queue.value
        if (!swapping && c.mediaItemCount > 0 && q.isNotEmpty()) {
            val playerIndex = c.currentMediaItemIndex
            // Radio (or a lone track) is pushed as a single Media3 item while the
            // panel still shows a full window around it, so Media3's index can't
            // be mapped straight onto [_queue] - that would repoint the active
            // item back to the head of the list. Only read back the index when
            // Media3 actually holds the whole queue.
            val oneToOne = c.mediaItemCount == q.size
            val logical = if (oneToOne) playerIndex.coerceIn(0, q.lastIndex) else _queueIndex.value
            if (logical != _queueIndex.value) {
                _queueIndex.value = logical
                PlaybackBus.publishStation(q[logical])
            }

            // A huge queue is played as a window; when that window is nearly
            // spent, roll it forward in [_source] so the library never silently
            // stops at the boundary. Guarded so the poller and onEvents can't
            // double-push (and skipped when Media3 holds a single radio item).
            if (oneToOne && _source.size > WINDOW && playerIndex >= c.mediaItemCount - 2) {
                val abs = windowBase + playerIndex
                val next = (abs + 1).coerceIn(0, _source.lastIndex)
                if (next > windowBase && next <= _source.lastIndex) {
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
    }

    fun play(station: Station, from: List<Station> = emptyList()) {
        var q = _queue.value
        if (from.isNotEmpty()) {
            _baseSource = from
            // Cap the queued list to a bounded window around the tapped track so
            // a huge source (the whole local library) doesn't flood the queue.
            // The active order is linear, or shuffled if shuffle is on; the
            // tapped track keeps playing first, so it heads the shuffled list.
            val order = if (_shuffle.value && from.all { it.isTrack }) shuffledKeepFirst(from, station) else from
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
            val order = if (_shuffle.value && q.all { it.isTrack }) shuffledKeepFirst(q, station) else q
            _source = order
            _queueIndex.value = order.indexOfFirst { it.url == station.url }
            windowBase = 0
        }
        val queue = q
        val start = windowBase + _queueIndex.value.coerceAtLeast(0)

        PlaybackBus.publishStation(station)
        PlaybackBus.publishError(null)
        PlaybackBus.publishFormat(StreamFormat())

        // The tapped station is published and rendered first, on the calling
        // thread, so the music screen and mini bar update the instant a song is
        // touched. MediaController insists its methods run on the main thread,
        // but launching on the plain Main dispatcher (not `immediate`) yields
        // to the looper first, so that already-published new title and plate
        // draw a frame before the blocking setMediaItems/prepare work runs.
        scope.launch(Dispatchers.Main) {
            val c = controller ?: return@launch
            // Any queue of finite tracks is a real playlist, so Media3 plays one
            // after another regardless of where they came from: local files and
            // provider albums alike. A live radio stream has no end to advance
            // from, so it is pushed as ONE Media3 item even though the queue
            // panel still shows the rest of its list as up-next to switch to.
            val allTracks = queue.all { it.isTrack } && queue.size > 1
            swapping = true
            try {
                if (allTracks) {
                    slideWindow(c, start)
                } else {
                    // Single live stream or lone track: Media3 holds just the
                    // tapped playable. The queue window was already computed
                    // above (lines 168-188) so the panel shows the neighbours,
                    // but the player advances nothing automatically.
                    c.setMediaItems(listOf(buildItem(station)), 0, 0L)
                }
                c.prepare()
                c.play()
            } finally {
                swapping = false
            }
            sync()
        }
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
        c.setMediaItems(items, index.coerceIn(0, items.lastIndex), 0L)
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
     * currently- or tapped-playing track is not interrupted while the rest of
     * the playlist plays in random order. Only meaningful for finite track
     * playlists; live-source order is left alone by its callers.
     */
    private fun shuffledKeepFirst(base: List<Station>, first: Station): List<Station> {
        val rest = base.filter { it.url != first.url }
        return listOf(first) + rest.shuffled()
    }

    /**
     * Toggles shuffled playback of the current list. When switched on, the list
     * the user is playing (local songs, favourites, a provider album - whatever
     * [_source] holds) is re-ordered so the tracked order of the list is
     * shuffled, always keeping the current track first. Toggling off restores
     * the original linear order from [_baseSource]. On a live stream or a lone
     * track there is nothing finite to reorder, so only the flag flips.
     */
    fun toggleShuffle() {
        val newOn = !_shuffle.value
        _shuffle.value = newOn
        val c = controller ?: return
        if (newOn) {
            // Nothing to reorder: a live stream (not all tracks) or single item.
            if (_source.size < 2 || !_source.all { it.isTrack }) { sync(); return }
            val current = _source.getOrNull(_queueIndex.value.takeIf { it >= 0 }?.let { windowBase + it } ?: 0)
                ?: _baseSource.firstOrNull()
                ?: _queue.value.firstOrNull()
                ?: _source.first()
            val reordered = shuffledKeepFirst(_baseSource.ifEmpty { _source }, current)
                .ifEmpty { _source }
            _shuffledSource = reordered
            _source = reordered
            // Re-window the queue from the shuffled order, current still playing.
            val target = reordered.indexOfFirst { it.url == current.url }.coerceAtLeast(0)
            scope.launch(Dispatchers.Main) {
                slideWindow(c, target)
                sync()
            }
        } else {
            val linear = _baseSource.ifEmpty { _shuffledSource ?: _source }
            _shuffledSource = null
            val current = _source.getOrNull(
                (_queueIndex.value.takeIf { it >= 0 }?.let { windowBase + it } ?: 0),
            ) ?: linear.firstOrNull()
            _source = linear.ifEmpty { listOf(current).filterNotNull() }
            val target = _source.indexOfFirst { it.url == current?.url }.coerceAtLeast(0)
            scope.launch(Dispatchers.Main) {
                if (_source.isNotEmpty()) slideWindow(c, target)
                sync()
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

    private fun step(delta: Int) {
        // Navigate within the full source list (not the bounded [_queue]
        // window), then re-slice a fresh queue around the chosen track, so prev
        // / next keep walking the whole library and never stray into another
        // list that may have been played before.
        val src = _source
        if (src.isEmpty()) return
        val here = windowBase + _queueIndex.value
        val abs = (here + delta).coerceIn(0, src.lastIndex)
        if (abs == here) return
        play(src[abs], src)
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
        c.seekTo((d * fraction.coerceIn(0f, 1f)).toLong())
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
