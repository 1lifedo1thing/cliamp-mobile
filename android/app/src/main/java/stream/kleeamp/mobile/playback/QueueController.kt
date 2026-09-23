package stream.kleeamp.mobile.playback

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
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlinx.coroutines.withTimeoutOrNull
import stream.kleeamp.mobile.KleeampApp
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.play.QueueModel
import stream.kleeamp.mobile.play.QueuePolicy
import stream.kleeamp.mobile.widget.WidgetRenderer
import java.io.IOException

private const val NAV_DEBOUNCE_MS = 180L
private const val WINDOW = QueuePolicy.WINDOW

/**
 * The queue half of playback: the source lists, the bounded Up Next window,
 * shuffle, navigation, edits and persistence. Media3 itself stays behind
 * [PlayerConnection], reached through constructor callbacks, so this class
 * never touches a controller directly.
 */
@UnstableApi
    // STEP 17 split this as far as safely possible; queue behavior is covered by QueuePolicyTest and UpNextEditsTest.
@Suppress("TooManyFunctions", "LargeClass")
internal class QueueController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val controller: () -> MediaController?,
    private val buildItem: suspend (Station) -> MediaItem,
    private val resumeAt: suspend (Station) -> Long,
    private val currentSpeed: () -> Float,
    private val onSync: () -> Unit,
    private val onQueueRestored: (Boolean, Boolean) -> Unit,
) {
    private val model = QueueModel()
    private val history = QueueHistory()


    init {
        val app = context.applicationContext as KleeampApp
        scope.launch {
            val prefs = app.prefs
            val history = prefs.history.first()
            val favs = prefs.favorites.first()
            if (model.fallback.isEmpty()) {
                model.fallback = history.ifEmpty { favs }
            }
            restoreQueue(prefs)
        }
    }


    val upNext: StateFlow<List<Station>> get() = model.upNext
    val upNextIndex: StateFlow<Int> get() = model.upNextIndex
    val currentUpNext: List<Station> get() = model.currentUpNext

    /**
     * The station behind a Media3 item id (item ids are station ids), or null.
     * Lets observers that fire mid-transition - before the published station
     * has caught up - resolve what is actually audible right now instead of
     * reading a stale bus value.
     */
    fun stationForMediaId(id: String): Station? = model.stationForMediaId(id)

    /** Set by the root with recent history so prev/next work from a fresh launch. */
    fun setFallbackSource(list: List<Station>) = model.setFallbackSource(list)


    /**
     * Cold-start queue restore: the last session's Up Next window becomes this
     * session's queue, published paused - never auto-played. The mini player
     * and the Up Next screen render the remembered list from the first
     * DataStore emission instead of flashing empty until something plays.
     * Skipped when a play already landed (e.g. auto-resume won the race).
     */
    private suspend fun restoreQueue(prefs: Prefs) {
        val window = prefs.queue.first()
        if (window.isEmpty() || model.currentUpNext.isNotEmpty()) return
        val idx = prefs.queueIndex.first().coerceIn(0, window.lastIndex)
        // The full source list is deliberately not persisted (it can be the
        // whole local library), so navigation walks the restored window. The
        // next real play rebases onto its own list as before.
        model.restoreWindow(window, idx)
        PlaybackBus.publishStation(window[idx])
        PlaybackBus.publishSource(window)
        val nav = navAvailability()
        onQueueRestored(nav.hasPrev, nav.hasNext)
    }


    /** Whether shuffled playback is switched on. */
    val shuffle: StateFlow<Boolean> get() = model.shuffle

    /** Pending window-roll-forward job, cancelled if the window advances sooner. */
    private var _extending: Job? = null


    /**
     * Raised while [play] is rebuilding the Media3 queue. [sync] maps Media3's
     * window index back onto the new logical queue, but during the transition
     * Media3 still holds the previous items, so a poller tick can collide the
     * old window with the fresh [_upNext] and jump to - and publish - the wrong
     * track (e.g. tapping a song in the middle of a list plays the first item).
     * While the queue swap is in flight sync stays out of that bookkeeping and
     * only refreshes transport state; [play] finalises the index itself.
     */
    @Volatile
    private var swapping = false


    /**
     * Coalesces rapid transport taps without lagging a plain tap. Each step
     * keeps an absolute target in [model.source] in [_navPending]. A burst of next /
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


    /**
     * Media jobs park unexpected failure as a playback error instead of
     * reaching the uncaught handler and killing the process. Cancellation
     * bypasses the handler by contract, so cooperative cancel keeps working;
     * the UI clears the error on the next successful play. The message stays
     * generic on purpose: exception text can carry signed stream URLs.
     */
    private val mediaFailureHandler = CoroutineExceptionHandler { _, e ->
        android.util.Log.e("kleeamp/player", "playback job failed", e)
        PlaybackBus.publishError("couldn't play this station")
    }

    // Shuffle's Media3 rebuild deliberately lives off [_navJob]. A toggle
    // followed immediately by prev/next (or an auto-advance) cancels [_navJob]
    // to stop navigation racing a stale rebuild; routing the shuffle rebuild
    // through [_navJob] meant that same cancel killed the shuffle window before
    // it was applied, snapping playback back to the linear queue. This job is
    // only cancelled by a new explicit play or a new toggle, so a shuffle
    // always lands.
    private var _shuffleJob: Job? = null


    /**
     * Rebuilds the [_upNext] window mirror to match exactly what Media3 is
     * actually holding, item by item, keyed on media id. Used when [_upNext] and
     * Media3's window have drifted out of phase - a near-tail roll that shrank
     * [_upNext] to the source tail while Media3 kept the old wider window, or a
     * shuffle realign that moved only [_upNext] - so the display always names the
     * same songs Media3 is playing, in the same order. Returns false when Media3
     * holds no items we can map back to [model.source] (a live stream we can't anchor).
     */
    private fun mirrorUpNextFromMedia3(c: Player): Boolean {
        val src = model.source
        if (src.isEmpty()) return false
        val count = c.mediaItemCount
        if (count == 0) return false
        val pi = c.currentMediaItemIndex.coerceIn(0, count - 1)
        val ids = (0 until count).map { c.getMediaItemAt(it).mediaId }
        fun matches(start: Int) = start >= 0 && start + count <= src.size &&
            ids.indices.all { src[start + it].id == ids[it] }
        val base = if (matches(model.windowBase)) model.windowBase else
            (0..(src.size - count)).firstOrNull(::matches) ?: return false
        val items = src.subList(base, base + count)
        val playerIdx = pi
        model.windowBase = base
        model.setWindow(items, playerIdx)
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
        val prefs = (context.applicationContext as KleeampApp).prefs
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
        val windows = QueuePolicy.widgetWindowIndices(
            model.source,
            model.windowBase,
            model.currentIndex,
            station.url,
            model.ringFallback,
        ) ?: return
        val prefs = (context.applicationContext as KleeampApp).prefs
        scope.launch {
            prefs.setWidgetSource(windows.window)
            prefs.setWidgetNext(windows.next)
        }
    }

    /**
     * The Up Next window plus the position inside it, so a cold start reopens
     * on the remembered queue. The window alone starts at the tapped track,
     * so up to [QueuePolicy.PREV_KEEP] predecessors from the source ride in front of it -
     * linear and clamped, never wrapped, so a finite list still reads in its
     * own order. Written at the same choke point as the widget window, so
     * both agree on what "now" means.
     */
    private fun persistQueue() {
        val q = model.currentUpNext
        if (q.isEmpty()) return
        val prefs = (context.applicationContext as KleeampApp).prefs
        val (combined, queueIndex) =
            QueuePolicy.persistQueueWindow(model.source, model.windowBase, q, model.currentIndex)
        scope.launch { prefs.setQueue(combined, queueIndex) }
    }


    /** Widget preview follows the same occurrence and finite tail as the Up Next screen. */
    internal fun upcomingStations(count: Int = 4): List<Station> =
        QueuePolicy.upcomingSlice(model.source, model.windowBase + model.currentIndex, model.ringFallback, count)

    /** An Up Next tap targets this occurrence, including when a URL occurs twice. */
    fun playUpNextEntry(index: Int) {
        if (index !in model.currentUpNext.indices) return
        _navTimerJob?.cancel()
        _navPending = model.windowBase + index
        applyNavigation()
    }

    /**
     * A list tap always starts that list at the tapped item: Up next is
     * rebuilt from the items that follow it in the list, in the list's order.
     * Manual Up next edits survive until the next list tap, never past it.
     */
    fun play(station: Station, from: List<Station> = emptyList()) {
        startPlayback(station, from, preserveOrder = false)
    }

    // Single play choke point: model swap plus Media3 queue build must stay atomic around the swapping flag.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun startPlayback(
        station: Station,
        from: List<Station>,
        preserveOrder: Boolean,
        sourceIndex: Int? = null,
    ) {
        cancelPendingPlayback()
        if (from.isNotEmpty()) {
            model.playFromList(station, from, preserveOrder, sourceIndex)
        } else {
            model.playFromWindow(station)
        }
        val upNext = model.currentUpNext
        val start = model.windowBase + model.currentIndex.coerceAtLeast(0)

        PlaybackBus.publishStation(station)
        PlaybackBus.publishSource(model.source)
        history.record(station)
        android.util.Log.d(
            "kleeamp/wid",
            "PLAY source.size=${model.source.size} station=${station.name} preserve=$preserveOrder",
        )
        persistWidgetWindow(station)
        persistQueue()
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
        swapping = controller() != null
        val job = scope.launch(Dispatchers.Main + mediaFailureHandler) {
            val c = controller() ?: return@launch
            // Any queue of finite tracks is a real playlist, so Media3 plays one
            // after another regardless of where they came from: local files and
            // provider albums alike. A live radio stream has no end to advance
            // from, so it is pushed as ONE Media3 item even though the queue
            // panel still shows the rest of its list as up-next to switch to.
            val allTracks = upNext.all { it.isTrack } && upNext.size > 1
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
                    val wq = upNext.map { it.id }
                    val alreadyLoaded = cq.size == wq.size && wq.indices.all { cq[it] == wq[it] }
                    if (alreadyLoaded) {
                        val target = model.currentIndex.coerceIn(0, c.mediaItemCount - 1)
                        val resume = upNext.getOrNull(target)?.let { resumeAt(it) } ?: 0L
                        c.seekTo(target, resume)
                    } else {
                        slideWindow(c, start)
                    }
                } else {
                    // Single live stream or lone track: Media3 holds just the
                    // tapped playable. The queue window was already computed
                    // above so the panel shows the neighbours. sync() advances
                    // finite items when they end; live radio waits for Next.
                    // A part-listened episode still opens where it was left.
                    c.setMediaItems(listOf(buildItem(station)), 0, resumeAt(station))
                }
                ensureActive()
                c.prepare()
                c.play()
                c.setPlaybackSpeed(currentSpeed())
            } finally {
                swapping = false
            }
            ensureActive()
            onSync()
        }
        _navJob = job
    }

    private fun cancelPendingPlayback() {
        _navJob?.cancel()
        _navTimerJob?.cancel()
        _navTimerJob = null
        _navPending = null
        _shuffleJob?.cancel()
        _extending?.cancel()
        _extending = null
        swapping = false
    }

    private suspend fun extendWindow(c: Player) {
        val source = model.source
        val oldUpNext = model.currentUpNext
        val oldBase = model.windowBase
        val end = oldBase + oldUpNext.size
        val newEnd = minOf(end + WINDOW - 2, source.size)
        if (end >= newEnd || !oldUpNext.all { it.isTrack }) return
        val extra = source.subList(end, newEnd)
        // A mixed queue is advanced by the ended callback, one item at a time.
        if (!extra.all { it.isTrack }) return
        val items = extra.map { buildItem(it) }
        if (model.source !== source || model.currentUpNext != oldUpNext || swapping) return
        val consumed = c.currentMediaItemIndex.coerceIn(0, oldUpNext.lastIndex)
        swapping = true
        try {
            c.addMediaItems(items)
            c.removeMediaItems(0, consumed)
            model.windowBase = oldBase + consumed
            model.setWindow(source.subList(model.windowBase, newEnd), 0)
        } finally {
            swapping = false
        }
        onSync()
    }

    /**
     * Advances the currently-windowed Media3 playlist to [start] in [model.source],
     * sliding [_upNext] to the matching bounded window. For short sources (whole
     * list fits) it pushes everything from [start] onward (or the whole list if
     * [start] is the tapped index and the list is short).
     */
    private suspend fun slideWindow(c: Player, start: Int) {
        val src = model.source
        if (src.isEmpty()) return
        val abs = start.coerceIn(0, src.lastIndex)
        val playWindow = model.rewindow(abs)
        val slice = playWindow.window
        val index = playWindow.index
        // Raised around the swap so the half-second poller's sync() can't run
        // between [_upNext] being repointed at the new window and Media3 actually
        // switching. Un-guarded, that interleaving resolves the still-playing
        // Media3 item against the fresh [_upNext], falls back to the raw index and
        // publishes a station that is not what is audible - "shows one song while
        // playing another". Once Media3 is set, [_upNext] and its index are coherent
        // so the consumer side of sync() is safe to resume.
        swapping = true
        try {
            // Tracks ride a real multi-item window so they auto-advance and
            // seek in place. Anything else (live radio, mixed walls) loads
            // exactly the audible item - same as play()'s single-item path -
            // so a next/prev never resolves dozens of streams and artworks it
            // will never play. The [_upNext] mirror above still holds the whole
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
     * Toggles shuffled playback of the current list. When switched on, the list
     * the user is playing (local songs, favourites, a provider album, a station
     * wall - whatever [model.source] holds) is re-ordered so the order of the list is
     * shuffled, always keeping the current item first. Toggling off restores
     * the original linear order from [model.baseSource]. On a lone item there is
     * nothing to reorder, so only the flag flips.
     */
    // Audibility-critical shuffle rebuild with live re-anchoring; splitting risks stop-the-world gaps.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun toggleShuffle() {
        // Model reorder plus a seamless Media3 head/tail swap. Both the model
        // and the player end up on the new order so sync() never sees a
        // mismatch (a mismatch is what made a cardboard shuffle either only
        // reach the loaded window - "a few random songs" - or show one song
        // while playing another) - but the audible item itself is never
        // touched, so there is no rebuffer or seek either.
        val newOn = !model.shuffleOn
        val c = controller()
        if (c == null || model.source.isEmpty()) { model.setShuffled(newOn); onSync(); return }
        val base = model.baseSource.ifEmpty { model.source }

        // Anchor "current" on Media3's LIVE audible item, not the model's
        // [_upNextIndex]. sync() reconciles the model to the player on a slow
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
        val currentAbs = model.windowBase + model.currentIndex
        val current = model.source.getOrNull(currentAbs)?.takeIf { it.id == audibleId }
            ?: model.source.firstOrNull { it.id == audibleId }
            ?: model.source.getOrNull(currentAbs)
            ?: model.baseSource.firstOrNull()
            ?: model.currentUpNext.firstOrNull()
            ?: model.source.first()
        model.setShuffled(newOn)
        val baseIndex = currentAbs.takeIf { base.getOrNull(it)?.url == current.url }
            ?: base.indexOfFirst { it.url == current.url }.coerceAtLeast(0)
        if (newOn) {
            if (model.source.size < 2) { onSync(); return }
            model.replaceOrder(QueuePolicy.shuffleReorder(base, baseIndex, current), base)
        } else {
            model.replaceOrder(base, base)
        }

        // Re-anchor the model immediately so the panel shows the new order
        // instantly - the same reason the stations path feels smooth. The
        // Media3 tail is then swapped around the still-playing item below,
        // without touching it, so there is no rebuffer or seek.
        _extending?.cancel()
        _extending = null
        _shuffleJob?.cancel()
        _shuffleJob = null
        val src = model.source
        val absJ = baseIndex
        val playWindow = model.rewindow(absJ)
        val slice = playWindow.window
        val idx = playWindow.index
        if (!(slice.all { it.isTrack } && slice.size > 1)) {
            // Anything else keeps the single-item shape play() gave it, and
            // that item is the current station itself: reordering the model
            // IS the shuffle, and swapping Media3 would only rebuffer the
            // same stream (plus resolve dozens of stations for nothing).
            onSync()
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
        _shuffleJob = scope.launch(Dispatchers.Main + mediaFailureHandler) {
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
                var p = controller() ?: return@launch
                if (p.mediaItemCount == 0) {
                    val all = withContext(Dispatchers.Default) {
                        anchorSlice.map { buildItem(it) }
                    }
                    ensureActive()
                    p.setMediaItems(all, anchorIdx.coerceIn(0, all.lastIndex), 0L)
                    ensureActive()
                    onSync()
                    return@launch
                }
                // The track may have rolled forward while the tail was
                // building; re-anchor on what is actually audible rather than
                // swapping a stale tail around the wrong song.
                var pi = p.currentMediaItemIndex.coerceIn(0, p.mediaItemCount - 1)
                var audibleNow = p.getMediaItemAt(pi).mediaId
                if (audibleNow != anchorId) {
                    val retry = model.source.firstOrNull { it.id == audibleNow }
                    if (retry != null) {
                        anchorId = retry.id
                        val abs2 = model.source.indexOfFirst { it.url == retry.url }.coerceAtLeast(0)
                        val retryWindow = model.rewindow(abs2)
                        anchorSlice = retryWindow.window
                        anchorIdx = retryWindow.index
                        headItems = withContext(Dispatchers.Default) {
                            anchorSlice.subList(0, anchorIdx).map { buildItem(it) }
                        }
                        ensureActive()
                        tailItems = withContext(Dispatchers.Default) {
                            anchorSlice.subList(anchorIdx + 1, anchorSlice.size).map { buildItem(it) }
                        }
                        ensureActive()
                        p = controller() ?: return@launch
                        if (p.mediaItemCount == 0) {
                            val all = withContext(Dispatchers.Default) {
                                anchorSlice.map { buildItem(it) }
                            }
                            ensureActive()
                            p.setMediaItems(
                                all,
                                anchorIdx.coerceIn(0, all.lastIndex),
                                p.currentPosition.coerceAtLeast(0),
                            )
                            ensureActive()
                            onSync()
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
                onSync()
            } finally {
                if (_shuffleJob === coroutineContext[Job]) swapping = false
                else if (_shuffleJob == null) swapping = false
            }
        }
    }

    /**
     * Next follows the active Up Next list, including edits made after stepping back.
     * A history redo is only a fallback when no active source exists.
     */
    fun next() {
        if (model.source.isNotEmpty()) {
            step(+1)
            return
        }
        // Claimed under lock so the goto's own record sees its tip and no-ops.
        val fwd = history.forward()
        if (fwd != null) {
            gotoHistory(fwd)
            return
        }
        step(+1)
    }

    /**
     * Prev walks the current context backward - the mirror of [next] - so it
     * behaves the same in every source and never leaves the list for another
     * context. Walking back past the first heard song keeps stepping into
     * earlier items with Up Next following. The heard trail is only a
     * fallback when nothing is loaded yet.
     */
    fun prev() {
        if (model.source.isNotEmpty()) {
            step(-1)
            return
        }
        val back = history.back()
        if (back != null) {
            gotoHistory(back)
            return
        }
        step(-1)
    }

    /**
     * Jumps to a history item without recording (the caller pre-stepped, so
     * the record is a duplicate by construction). Reuses [applyNavigation]'s
     * fast path when the item lives in the current context, else starts it
     * as a fresh single - a foreign context, same as tapping it would.
     */
    private fun gotoHistory(station: Station) {
        model.ringFallback = false
        // A manual jump supersedes any coalescing burst still waiting.
        _navTimerJob?.cancel()
        _navTimerJob = null
        val target = history.gotoTarget(station, model.source, model.fallback)
        if (target != null) {
            _navPending = target
            applyNavigation()
        } else {
            play(station)
        }
    }

    /**
     * Moves prev/next by [delta]. Each tap targets exactly one song forward or
     * back from the currently committed track. Rapid taps coalesce on a single
     * pending absolute target so parallel Media3 re-queues can't race each other
     * and skip past the song you actually tapped to.
     */
    // Coalesced tap targeting across pending, model, live and bus positions in one place.
    private fun step(delta: Int) {
        val src = model.source.ifEmpty { model.fallback }
        if (src.isEmpty()) return
        // Base prev/next on the latest pending target (if any) rather than the
        // committed index, so rapid taps stack onto one another instead of all
        // collapsing onto the same song. Either way the value is an absolute
        // index into [model.source].
        val pending = _navPending
        // The model records an explicit selection before Media3 finishes
        // loading it. Prefer that occurrence over a first-URL match, which
        // would rewind duplicate songs. Media3 and the bus are fallbacks for
        // playback restored outside this session's queue.
        val busUrl = PlaybackBus.station.value?.url
        val liveHere = controller()?.let { c ->
            if (c.mediaItemCount > 1) model.windowBase + c.currentMediaItemIndex else null
        }
        val here = QueuePolicy.resolveHere(
            pending,
            QueuePolicy.modelHereIndex(model.windowBase, model.currentIndex, src, busUrl),
            liveHere,
            QueuePolicy.busHereIndex(src, busUrl),
        )
        val mode = if (model.source.isEmpty() && pending == null) {
            val shown = (PlaybackBus.station.value ?: src.firstOrNull())?.let { s ->
                src.indexOfFirst { it.url == s.url }
            } ?: -1
            // Launch fallback: walk history as a ring so the first prev/next
            // from the restored song have somewhere to go.
            QueuePolicy.StepMode.Cold(shown)
        } else {
            QueuePolicy.stepMode(model.source.isEmpty(), pending != null, model.ringFallback, shown = -1)
        }
        if (mode != QueuePolicy.StepMode.Linear) model.ringFallback = true
        val abs = QueuePolicy.stepTarget(mode, src.size, here, delta)
        if (abs == here && model.source.isNotEmpty() && pending == null) return

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
        val src = model.source.ifEmpty { model.fallback }
        if (src.isEmpty()) return
        val abs = if (model.ringFallback && src.size > 1) ((target % src.size) + src.size) % src.size
        else target.coerceIn(0, src.lastIndex)
        val station = src[abs]
        val targetInWindow = abs in model.windowBase until (model.windowBase + model.currentUpNext.size)
        if (targetInWindow) model.setIndex(abs - model.windowBase)

        // Publish and persist the target exactly as play() would, so the UI and
        // the widget agree the instant the song is tapped - even before Media3
        // has switched over.
        PlaybackBus.publishStation(station)
        PlaybackBus.publishSource(src)
        history.record(station)
        PlaybackBus.publishError(null)
        PlaybackBus.publishFormat(StreamFormat())
        persistWidgetWindow(station)
        persistAndRefresh(station)

        val q = model.currentUpNext
        val c = controller()
        val inWindow = c != null && !swapping && q.isNotEmpty() &&
            abs >= model.windowBase && abs < model.windowBase + q.size
        if (inWindow) {
            val windowIndex = (abs - model.windowBase)
            _navJob?.cancel()
            swapping = true
            val job = scope.launch(Dispatchers.Main + mediaFailureHandler) {
                val player = controller() ?: return@launch
                // Only seek in place when the target is actually loaded by Media3;
                // a bare seekTo clamps to the last loaded item when the index is
                // out of range, silently freezing playback on that song. Otherwise
                // slide the window so the tapped song becomes the audible item.
                if (player.mediaItemCount == q.size &&
                    player.getMediaItemAt(windowIndex).mediaId == station.id
                ) {
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
                    model.setIndex(windowIndex)
                } else {
                    slideWindow(player, abs)
                    player.prepare()
                    player.play()
                }
                ensureActive()
                onSync()
            }
            _navJob = job
        } else {
            startPlayback(station, src, preserveOrder = true, sourceIndex = abs)
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

    /** Apply a window edit without losing the unloaded continuation of a long list. */
    private fun applyUpNextToPlayer(
        previousUpNext: List<Station>,
        edit: ((MediaController) -> Unit)? = null,
    ) {
        cancelPendingPlayback()
        val q = model.currentUpNext
        model.ringFallback = false
        val idx = if (q.isEmpty()) -1 else model.currentIndex.coerceIn(0, q.lastIndex)
        val head = model.source.take(model.windowBase)
        val tail = model.source.drop(model.windowBase + previousUpNext.size)
        model.source = head + q + tail
        model.baseSource = model.source
        model.setIndex(idx)
        PlaybackBus.publishSource(model.source)
        PlaybackBus.station.value?.let(::persistWidgetWindow)
        swapping = controller() != null
        _navJob = scope.launch(Dispatchers.Main + mediaFailureHandler) {
            val c = controller() ?: return@launch
            try {
                val canEdit = edit != null && q.all { it.isTrack } &&
                    c.mediaItemCount == previousUpNext.size &&
                    previousUpNext.indices.all { c.getMediaItemAt(it).mediaId == previousUpNext[it].id }
                if (q.isEmpty()) {
                    c.clearMediaItems()
                } else if (canEdit) {
                    // Move/remove upcoming items without restarting the audible item.
                    edit(c)
                } else {
                    val position = if (c.currentMediaItem?.mediaId == q[idx].id) c.currentPosition else 0L
                    if (q.size > 1 && q.all { it.isTrack }) {
                        val items = q.map { buildItem(it) }
                        c.setMediaItems(items, idx, position)
                        c.prepare()
                    } else if (c.mediaItemCount != 1 || c.currentMediaItem?.mediaId != q[idx].id) {
                        c.setMediaItems(listOf(buildItem(q[idx])), 0, position)
                        c.prepare()
                    }
                }
            } finally {
                swapping = false
            }
            onSync()
        }
    }

    /** Insert without changing the originating list. Append means the full tail, not just its window. */
    fun addToUpNext(station: Station, at: Int = Int.MAX_VALUE) {
        val previous = model.currentUpNext
        if (at == Int.MAX_VALUE && model.windowBase + previous.size < model.source.size) {
            _extending?.cancel()
            model.source = model.source + station
            model.baseSource = model.source
            PlaybackBus.publishSource(model.source)
            onSync()
            return
        }
        val inserted = QueuePolicy.insert(previous, at, model.currentIndex, station)
        model.setWindow(inserted.queue, inserted.currentIndex)
        applyUpNextToPlayer(previous)
    }

    /** Insert [station] right after the currently-playing item (play next). */
    fun playNext(station: Station) {
        addToUpNext(station, QueuePolicy.playNextInsertAt(model.currentUpNext.size, model.currentIndex))
    }

    /** Play [station] with [from] as a brand-new Up Next list, replacing whatever was there. */
    fun replaceUpNext(station: Station, from: List<Station>) {
        play(station, from)
    }

    /** Drop the station at [index], keeping the playing item stable. */
    fun removeFromUpNext(index: Int) {
        val q = model.currentUpNext
        if (index !in q.indices) return
        val (newQ, newIdx) = QueuePolicy.remove(q, index, model.currentIndex)
        model.setWindow(newQ, newIdx)
        applyUpNextToPlayer(previousUpNext = q, edit = { it.removeMediaItem(index) })
    }

    /** Move the station at [from] to [to], keeping the playing item stable. */
    fun reorderUpNext(from: Int, to: Int) {
        val q = model.currentUpNext
        if (from !in q.indices || to !in q.indices || from == to) return
        val qi = model.currentIndex
        val item = q[from]
        val moved = q.toMutableList().apply {
            removeAt(from)
            add(to, item)
        }
        model.setWindow(
            moved,
            // the playing station follows its item through the move
            upNextIndexAfterMove(qi, from, to),
        )
        applyUpNextToPlayer(previousUpNext = q, edit = { it.moveMediaItem(from, to) })
    }

    /** Clear only pending playback; keep the audible item and its source identity. */
    fun clearUpNext() {
        val previous = model.currentUpNext
        val currentIndex = model.currentIndex
        val (kept, keptIdx) = QueuePolicy.clearPending(previous, currentIndex)
        model.setWindow(kept, keptIdx)
        model.source = kept
        model.windowBase = 0
        applyUpNextToPlayer(previousUpNext = previous, edit = { player ->
            player.removeMediaItems(currentIndex + 1, player.mediaItemCount)
            if (currentIndex > 0) player.removeMediaItems(0, currentIndex)
        })
    }

    internal val isTransitioning: Boolean
        get() = swapping || _navJob?.isActive == true || _shuffleJob?.isActive == true

    /** Prev/next availability for the transport state; mirrors the nav math sync reads. */
    internal fun navAvailability(): QueuePolicy.NavAvailability {
        val nav = model.source.ifEmpty { model.fallback }
        val busUrl = PlaybackBus.station.value?.url ?: nav.firstOrNull()?.url
        return QueuePolicy.navAvailability(
            model.source,
            model.fallback,
            model.ringFallback,
            model.windowBase + model.currentIndex,
            QueuePolicy.busHereIndex(nav, busUrl),
            QueuePolicy.Trail(history.size, history.index),
        )
    }

    /**
     * Reconciles the Up Next window against what Media3 is actually holding,
     * publishing when the audible item genuinely changed. Called from sync.
     */
    // Media3-to-model reconciliation with id-based resolution; splitting risks shows-wrong-song bugs.
    @Suppress("CyclomaticComplexMethod")
    internal fun reconcileMediaWindow(c: Player, changingPlayback: Boolean) {
        val q = model.currentUpNext
        if (!changingPlayback && c.mediaItemCount > 0 && q.isNotEmpty()) {
            val playerIndex = c.currentMediaItemIndex.coerceIn(0, c.mediaItemCount - 1)
            // Radio (or a lone track) is pushed as a single Media3 item while the
            // panel still shows a full window around it, so Media3's index can't
            // be mapped straight onto [_upNext] - that would repoint the active
            // item back to the head of the list. Only read back the index when
            // Media3 actually holds the whole queue.
            val oneToOne = c.mediaItemCount == q.size
            val curId = c.getMediaItemAt(playerIndex).mediaId
            // Resolve which queued station is actually audible. For a real
            // playlist [_upNext] must mirror Media3's window exactly, so the
            // currently-playing item is at [_upNext][playerIndex] - by id, not by
            // a stored index. A raw stored index is what caused "shows one song
            // while playing another": when a near-tail roll shrank [_upNext] to
            // the source tail while Media3 kept the old wider window (or shuffle
            // drifted the two), [_upNextIndex] and Media3's index no longer named
            // the same item. Rebuilding [_upNext] from Media3's actual window
            // keeps the display honest in every case. A radio / lone-track item
            // (Media3 count == 1) deliberately plays a full panel window around
            // a single Media3 item, so we find it by id instead of collapsing.
            val resolvedIndex: Int? = if (oneToOne &&
                q.getOrNull(playerIndex)?.id == curId
            ) {
                playerIndex
            } else if (c.mediaItemCount == 1) {
                model.currentIndex.takeIf { q.getOrNull(it)?.id == curId }
                    ?: q.indexOfFirst { it.id == curId }.takeIf { it >= 0 }
            } else if (mirrorUpNextFromMedia3(c)) {
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
                val resolved = model.currentUpNext.getOrNull(resolvedIndex)
                model.setIndex(resolvedIndex)
                if (resolved != null && resolved.id != PlaybackBus.station.value?.id) {
                    PlaybackBus.publishStation(resolved)
                    // The heard trail learns Media3-side moves here - with
                    // the resolved index - never from raw transitions: a
                    // superseded seek's late transition would fork the trail
                    // with a ghost entry, and Prev would jump and loop.
                    history.record(resolved)
                }
            }

            // Append the next window and discard only played entries. Never seek
            // to the next song early or restart the audible item at a boundary.
            if (oneToOne && model.windowBase + q.size < model.source.size &&
                model.currentIndex >= q.size - 2 && _extending?.isActive != true
            ) {
                _extending = scope.launch(Dispatchers.Main + mediaFailureHandler) { extendWindow(c) }
            }
        }
    }

    /**
     * Advances one item when a finite window plays to its end before the next
     * window could be appended. Called from sync.
     */
    internal fun advanceOnEnded(changingPlayback: Boolean, ended: Boolean) {
        if (!changingPlayback && ended &&
            model.currentUpNext.getOrNull(model.currentIndex)?.isTrack == true &&
            model.windowBase + model.currentIndex < model.source.lastIndex
        ) {
            _navTimerJob?.cancel()
            _navPending = model.windowBase + model.currentIndex + 1
            applyNavigation()
        }
    }
}
