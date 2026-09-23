package stream.kleeamp.mobile.play

import stream.kleeamp.mobile.data.Station
// after STEP 07 this import becomes stream.kleeamp.mobile.model.Station

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
}
