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
     * Logical index (into [_queue]) of the first item pushed to Media3. When a
     * queue is huge - hundreds of local songs - the tapped track plus a small
     * tail is pushed instead of the whole thing, so play starts instantly. The
     * offset maps Media3's window back onto the logical queue in [sync].
     */
    private var windowBase = 0

    /** Pending window-roll-forward job, cancelled if the window advances sooner. */
    private var _extending: Job? = null

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
        val q = _queue.value
        if (c.mediaItemCount > 0 && q.isNotEmpty()) {
            val playerIndex = c.currentMediaItemIndex
            val logical = windowBase + playerIndex
            if (logical in q.indices && logical != _queueIndex.value) {
                _queueIndex.value = logical
                PlaybackBus.publishStation(q[logical])
            }

            // A huge queue is played as a window; when that window is nearly
            // spent, roll it forward so the album never silently stops at the
            // boundary. Guarded so the poller and onEvents can't double-push.
            if (q.size > WINDOW && playerIndex >= c.mediaItemCount - 2) {
                val next = (windowBase + playerIndex + 1).coerceIn(0, q.lastIndex)
                if (next > windowBase && next <= q.lastIndex) {
                    _extending?.cancel()
                    _extending = scope.launch {
                        delay(1500)
                        val c2 = controller ?: return@launch
                        if (c2.currentMediaItemIndex >= c2.mediaItemCount - 2 && next < q.size) {
                            pushWindow(c2, q, next)
                            sync()
                        }
                    }
                }
            }
        }

        val qi = _queueIndex.value
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
            hasPrev = qi > 0,
            hasNext = qi >= 0 && qi < _queue.value.lastIndex,
        )
    }

    fun play(station: Station, from: List<Station> = emptyList()) {
        var q = _queue.value
        if (from.isNotEmpty()) {
            _queue.value = from
            q = from
            _queueIndex.value = from.indexOfFirst { it.url == station.url }
        } else if (q.none { it.url == station.url }) {
            _queue.value = listOf(station)
            q = listOf(station)
            _queueIndex.value = 0
        } else {
            _queueIndex.value = q.indexOfFirst { it.url == station.url }
        }
        val queue = q

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
            // Any queue of finite tracks is a real playlist, so Media3 plays
            // one after another regardless of where they came from: local files
            // and provider albums alike. Radio queues stay single-item, because
            // a live stream has no end to advance from and pre-resolving sixty
            // station URLs would be waste.
            val playlist = queue.takeIf { list -> list.all { it.isTrack } && list.size > 1 }
            if (playlist != null) {
                pushWindow(c, playlist, _queueIndex.value)
            } else {
                // A single track (or live stream) is pushed as one media item,
                // so Media3's index 0 has to map back to the tapped station in
                // [_queue], not to the head of the list that produced it. This
                // keeps sync() publishing the tapped song instead of the top
                // search match whenever a search result is played alone.
                windowBase = _queueIndex.value
                c.setMediaItem(PlaybackService.mediaItem(context, station, StreamResolver.resolve(station.url)))
            }
            c.prepare()
            c.play()
            sync()
        }
    }

    /**
     * Pushes [queue] into Media3. For small queues the whole list goes in; for
     * a huge one (hundreds of songs) only [WINDOW] covers starting at [start],
     * so playback begins immediately instead of waiting for Media3 to set up
     * every track. [sync] maps the window back onto [start] when it advances.
     */
    private suspend fun pushWindow(c: Player, queue: List<Station>, start: Int) {
        windowBase = if (queue.size > WINDOW) start else 0
        val window = if (queue.size > WINDOW) {
            queue.subList(start, minOf(start + WINDOW, queue.size))
        } else {
            queue
        }
        val items = window.map { s ->
            PlaybackService.mediaItem(context, s, StreamResolver.resolve(s.url))
        }
        val index = if (queue.size > WINDOW) (_queueIndex.value - start) else _queueIndex.value
        c.setMediaItems(items, index.coerceIn(0, items.lastIndex), 0L)
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause()
        else {
            if (c.mediaItemCount == 0) {
                PlaybackBus.station.value?.let { play(it) }
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
        val q = _queue.value
        if (q.isEmpty()) return
        val i = (_queueIndex.value + delta).coerceIn(0, q.lastIndex)
        if (i == _queueIndex.value) return
        play(q[i], q)
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
        sync()
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
        sync()
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
        sync()
    }

    fun clearQueue() {
        if (_queue.value.isEmpty()) return
        _queue.value = emptyList()
        _queueIndex.value = -1
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
