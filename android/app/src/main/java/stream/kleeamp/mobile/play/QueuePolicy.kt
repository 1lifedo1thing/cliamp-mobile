package stream.kleeamp.mobile.play

import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.wrapNext

/**
 * Pure queue math behind [stream.kleeamp.mobile.playback.PlayerConnection].
 * No MediaController, no Context, no coroutines — list in, list out, so it
 * is unit-testable without Android.
 *
 * Behavior encoded here (do not change values):
 * - play from a list → Up next is the items from the tapped index forward
 * - play next inserts right after the current item
 * - add to queue appends
 * - clear pending keeps only the audible item
 * - huge sources play from a bounded window ([WINDOW]), cold-start persist
 *   keeps [PREV_KEEP] predecessors in front of it
 * - shuffle reorders a copy with the current item first; off restores base
 */
object QueuePolicy {
    /** Queues larger than this play lazily from a window, not in full. */
    const val WINDOW = 60

    /** Predecessors kept in front of a persisted queue window. */
    const val PREV_KEEP = 8

    /** Sentinel for [insert] meaning "append", mirroring addToUpNext's default. */
    const val APPEND = Int.MAX_VALUE

    /** Window of [order] beginning at [srcIndex], plus where it starts and the index inside it. */
    data class PlayWindow(
        val window: List<Station>,
        val base: Int,
        val index: Int,
    )

    /** Result of inserting into the visible queue: the new list and the shifted current index. */
    data class QueueInsert(
        val queue: List<Station>,
        val currentIndex: Int,
    )

    /**
     * Bounded window into [source] beginning at [srcIndex], capped at [window]
     * when the source is huge; short sources are returned whole.
     */
    fun sliceAt(source: List<Station>, srcIndex: Int, window: Int = WINDOW): List<Station> {
        if (source.size <= window) return source
        val start = srcIndex.coerceIn(0, source.lastIndex)
        val end = minOf(start + window, source.size)
        return source.subList(start, end)
    }

    /**
     * Play-from-list window for a resolved [srcIndex] (0..lastIndex): the
     * items from the tapped index forward, with the base offset and the
     * tapped index inside the window.
     */
    fun playWindow(order: List<Station>, srcIndex: Int, window: Int = WINDOW): PlayWindow {
        val base = if (order.size > window) srcIndex else 0
        val w = sliceAt(order, srcIndex, window)
        return PlayWindow(w, base, (srcIndex - base).coerceIn(0, w.lastIndex.coerceAtLeast(0)))
    }

    /** Shuffled copy of [base] with [first] kept at the front. Never mutates [base]. */
    fun shuffledKeepFirst(base: List<Station>, first: Station): List<Station> {
        val firstIndex = base.indexOfFirst { it.url == first.url }
        val rest = base.filterIndexed { index, _ -> index != firstIndex }
        return listOf(first) + rest.shuffled()
    }

    /** Where play-next lands: right after current, or appended when nothing is current. */
    fun playNextInsertAt(queueSize: Int, currentIndex: Int): Int =
        if (currentIndex in 0 until queueSize) currentIndex + 1 else queueSize

    /**
     * Insert [item] into [queue] at [at] ([APPEND] appends), shifting the
     * current index when the insert lands at or before it.
     */
    fun insert(queue: List<Station>, at: Int, currentIndex: Int, item: Station): QueueInsert {
        val pos = if (at == APPEND) queue.size else at.coerceIn(0, queue.size)
        val q = queue.toMutableList().apply { add(pos, item) }
        return QueueInsert(q, if (pos <= currentIndex) currentIndex + 1 else currentIndex)
    }

    /**
     * Current index after dropping [index]: follows the item through the
     * removal, -1 when the last item goes away.
     */
    fun indexAfterRemove(index: Int, currentIndex: Int, newSize: Int): Int = when {
        index < currentIndex -> (currentIndex - 1).coerceAtLeast(-1)
        index == currentIndex && newSize == 0 -> -1
        else -> currentIndex
    }

    /**
     * Drop [index] from [queue]: returns the filtered list with the current
     * index following its item. Out-of-range removals are a no-op.
     */
    fun remove(queue: List<Station>, index: Int, currentIndex: Int): Pair<List<Station>, Int> {
        if (index !in queue.indices) return Pair(queue, currentIndex)
        val newQ = queue.filterIndexed { i, _ -> i != index }
        return Pair(newQ, indexAfterRemove(index, currentIndex, newQ.size))
    }

    /** Clear pending playback: keep only the audible item and its source identity. */
    fun clearPending(queue: List<Station>, currentIndex: Int): Pair<List<Station>, Int> {
        val current = queue.getOrNull(currentIndex)
        return Pair(listOfNotNull(current), if (current == null) -1 else 0)
    }

    /**
     * Cold-start persist window for [window] (at [windowBase] in [source]):
     * up to [prevKeep] predecessors ride in front so both prev and next
     * survive a restart. Returns the combined list and the index inside it.
     * Callers skip empty windows, as before.
     */
    fun persistQueueWindow(
        source: List<Station>,
        windowBase: Int,
        window: List<Station>,
        currentIndex: Int,
        prevKeep: Int = PREV_KEEP,
    ): Pair<List<Station>, Int> {
        val idx = currentIndex.coerceIn(0, window.lastIndex)
        val runUp = source.takeIf { it.size > window.size }
            ?.subList((windowBase - prevKeep).coerceAtLeast(0), windowBase)
            ?: emptyList()
        return Pair(runUp + window, runUp.size + idx)
    }

    /** Prev/next availability: the transport's hasPrev/hasNext inputs. */
    data class NavAvailability(
        val hasPrev: Boolean,
        val hasNext: Boolean,
    )

    /**
     * Which list prev/next walks, from navigation state. Mirrors the sync
     * reader: the live source once anything has played, else the seeded
     * fallback so stepping works from the song shown at launch.
     */
    fun navAvailability(
        source: List<Station>,
        fallback: List<Station>,
        ringFallback: Boolean,
        absoluteIndex: Int,
        upNextIndex: Int,
        busStationUrl: String?,
        pastSize: Int,
        pastIndex: Int,
    ): NavAvailability {
        val nav = source.ifEmpty { fallback }
        val ring = nav.size > 1 && (source.isEmpty() || ringFallback)
        val navIdx = if (ring) -1
        else if (source.isNotEmpty() || upNextIndex >= 0) absoluteIndex
        else {
            val url = busStationUrl ?: nav.firstOrNull()?.url
            url?.let { u -> nav.indexOfFirst { it.url == u } } ?: -1
        }
        return NavAvailability(
            hasPrev = if (ring) true else (source.isEmpty() && pastIndex > 0) ||
                (nav.isNotEmpty() && navIdx > 0),
            hasNext = if (ring) true else (source.isEmpty() && pastIndex < pastSize - 1) ||
                (nav.size > 1 && navIdx in 0 until nav.lastIndex),
        )
    }

    /**
     * Which walk prev/next uses. Cold means nothing played yet and no tap is
     * pending: the fallback walks as a ring from the shown item ([shown], -1
     * when unknown). Ring wraps the live walk; Linear clamps it.
     */
    sealed interface StepMode {
        data class Cold(val shown: Int) : StepMode
        data object Ring : StepMode
        data object Linear : StepMode
    }

    fun stepMode(
        sourceEmpty: Boolean,
        hasPending: Boolean,
        ringFallback: Boolean,
        shown: Int,
    ): StepMode = when {
        sourceEmpty && !hasPending -> StepMode.Cold(shown)
        sourceEmpty || ringFallback -> StepMode.Ring
        else -> StepMode.Linear
    }

    /**
     * Absolute target for one prev/next step of [delta]. Ring wraps (positive
     * modulo, so negative deltas stay in range); Linear clamps; Cold walks
     * from the shown item or from the head when it is unknown.
     */
    fun stepTarget(mode: StepMode, srcSize: Int, here: Int, delta: Int): Int {
        fun wrap(k: Int) = ((k % srcSize) + srcSize) % srcSize
        return when (mode) {
            is StepMode.Cold ->
                if (mode.shown >= 0) wrap(mode.shown + delta)
                else (0 + delta).coerceIn(0, srcSize - 1)
            StepMode.Ring -> wrap(here + delta)
            StepMode.Linear -> (here + delta).coerceIn(0, srcSize - 1)
        }
    }

    /**
     * Committed position for one step: the pending tap target wins so rapid
     * taps stack, then the model occurrence (never a first-URL match, which
     * would rewind duplicates), then the live player and the bus. Zero when
     * nothing is known.
     */
    fun resolveHere(pending: Int?, modelHere: Int?, liveHere: Int?, busHere: Int?): Int =
        pending ?: modelHere ?: liveHere ?: busHere ?: 0

    /** Index of [busUrl] in [src], or -1 when absent. */
    fun busHereIndex(src: List<Station>, busUrl: String?): Int? =
        busUrl?.let { url -> src.indexOfFirst { it.url == url }.takeIf { it >= 0 } }

    /** The model occurrence, only when it still names the bus station. */
    fun modelHereIndex(windowBase: Int, upNextIndex: Int, src: List<Station>, busUrl: String?): Int? =
        (windowBase + upNextIndex).takeIf { src.getOrNull(it)?.url == busUrl }

    /** Widget preview: the same occurrence and finite tail as the Up Next screen. */
    fun upcomingSlice(
        source: List<Station>,
        absoluteIndex: Int,
        ringFallback: Boolean,
        count: Int = 4,
    ): List<Station> {
        if (absoluteIndex !in source.indices) return emptyList()
        return if (ringFallback) source.wrapNext(absoluteIndex, count)
        else source.drop(absoluteIndex + 1).take(count)
    }

    /** Widget persist rows: the wrapped window around the anchor plus the next row. */
    data class WidgetWindows(
        val window: List<Station>,
        val next: List<Station>,
    )

    /**
     * Anchor the widget window on [stationUrl] (the window occurrence first,
     * then a first-URL match), with both sides wrapped positive-modulo: a
     * short source is a ring here, so negative indices floor into range
     * instead of throwing. Null when the station is not in the source.
     */
    fun widgetWindowIndices(
        source: List<Station>,
        windowBase: Int,
        upNextIndex: Int,
        stationUrl: String,
        ringFallback: Boolean,
        before: Int = 8,
        after: Int = 8,
        nextCount: Int = 4,
    ): WidgetWindows? {
        if (source.isEmpty()) return null
        val i = (windowBase + upNextIndex).takeIf { source.getOrNull(it)?.url == stationUrl }
            ?: source.indexOfFirst { it.url == stationUrl }
        if (i < 0) return null
        val n = source.size
        fun wrap(k: Int) = ((i + k) % n + n) % n
        val win = (-before..after).map { source[wrap(it)] }
        val next = if (ringFallback) source.wrapNext(i, nextCount)
        else source.drop(i + 1).take(nextCount)
        return WidgetWindows(win, next)
    }

    /**
     * Shuffled copy of [base] keeping [anchor] at [anchorIndex]: the running
     * item never moves while the rest plays in random order. Never mutates
     * [base]; turning shuffle off restores [base] itself.
     */
    fun shuffleReorder(base: List<Station>, anchorIndex: Int, anchor: Station): List<Station> {
        val rest = base.filterIndexed { i, _ -> i != anchorIndex }.shuffled()
        val reordered = ArrayList<Station>(base.size)
        var ri = 0
        for (i in base.indices) {
            if (i == anchorIndex) reordered.add(anchor) else reordered.add(rest[ri++])
        }
        return reordered
    }
}
