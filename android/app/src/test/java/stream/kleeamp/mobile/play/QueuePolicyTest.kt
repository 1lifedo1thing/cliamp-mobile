package stream.kleeamp.mobile.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

class QueuePolicyTest {
    private fun station(i: Int) = Station(
        id = "id-$i",
        name = "s$i",
        url = "url-$i",
        source = StationSource.Local,
    )

    private fun list(n: Int) = (0 until n).map(::station)

    @Test
    fun windowAndPrevKeepAreUnchanged() {
        assertEquals(60, QueuePolicy.WINDOW)
        assertEquals(8, QueuePolicy.PREV_KEEP)
    }

    @Test
    fun playFromShortListKeepsWholeListFromTappedIndex() {
        val from = list(10)
        val w = QueuePolicy.playWindow(from, 4)
        assertEquals(from, w.window)
        assertEquals(0, w.base)
        assertEquals(4, w.index)
    }

    @Test
    fun playFromHugeListWindowsForwardFromTappedIndex() {
        val from = list(200)
        val w = QueuePolicy.playWindow(from, 100)
        assertEquals(60, w.window.size)
        assertEquals(from[100], w.window.first())
        assertEquals(from[159], w.window.last())
        assertEquals(100, w.base)
        assertEquals(0, w.index)
        // Near the tail the window runs to the end of the source.
        val tail = QueuePolicy.playWindow(from, 150)
        assertEquals(50, tail.window.size)
        assertEquals(from[150], tail.window.first())
        assertEquals(from[199], tail.window.last())
        assertEquals(150, tail.base)
        assertEquals(0, tail.index)
    }

    @Test
    fun sliceAtCapsHugeSources() {
        val small = list(10)
        assertEquals(small, QueuePolicy.sliceAt(small, 0))
        val from = list(200)
        assertEquals(60, QueuePolicy.sliceAt(from, 0).size)
        assertEquals(from[10], QueuePolicy.sliceAt(from, 10).first())
    }

    @Test
    fun playNextInsertsRightAfterCurrent() {
        assertEquals(3, QueuePolicy.playNextInsertAt(5, 2))
        // Nothing current: appends.
        assertEquals(5, QueuePolicy.playNextInsertAt(5, -1))
        assertEquals(0, QueuePolicy.playNextInsertAt(0, -1))
    }

    @Test
    fun addToQueueAppendsAndKeepsIndex() {
        val q = list(3)
        val r = QueuePolicy.insert(q, QueuePolicy.APPEND, 1, station(99))
        assertEquals(4, r.queue.size)
        assertEquals("url-99", r.queue.last().url)
        assertEquals(1, r.currentIndex)
    }

    @Test
    fun insertAtOrBeforeCurrentShiftsIndex() {
        val q = list(3)
        assertEquals(2, QueuePolicy.insert(q, 1, 1, station(99)).currentIndex)
        assertEquals(1, QueuePolicy.insert(q, 2, 1, station(99)).currentIndex)
        // Explicit positions clamp into range.
        assertEquals(4, QueuePolicy.insert(q, 100, 0, station(99)).queue.size)
    }

    @Test
    fun removeEarlierItemShiftsIndexBack() {
        val (newQ, newIdx) = QueuePolicy.remove(list(4), 0, 2)
        assertEquals(3, newQ.size)
        assertEquals(1, newIdx)
    }

    @Test
    fun removeCurrentKeepsIndexOnNextItem() {
        val (_, newIdx) = QueuePolicy.remove(list(4), 1, 1)
        assertEquals(1, newIdx)
    }

    @Test
    fun removeLastItemLeavesMinusOne() {
        val (newQ, newIdx) = QueuePolicy.remove(list(1), 0, 0)
        assertTrue(newQ.isEmpty())
        assertEquals(-1, newIdx)
    }

    @Test
    fun clearPendingKeepsOnlyCurrent() {
        val q = list(5)
        val (kept, idx) = QueuePolicy.clearPending(q, 2)
        assertEquals(listOf(q[2]), kept)
        assertEquals(0, idx)
    }

    @Test
    fun clearPendingWithNoCurrentEmpties() {
        val (kept, idx) = QueuePolicy.clearPending(list(3), -1)
        assertTrue(kept.isEmpty())
        assertEquals(-1, idx)
    }

    @Test
    fun shuffleKeepsFirstAndSameSongsWithoutMutatingBase() {
        val base = list(10)
        val first = base[4]
        val shuffled = QueuePolicy.shuffledKeepFirst(base, first)
        assertEquals(first, shuffled.first())
        assertEquals(base.map { it.url }.toSet(), shuffled.map { it.url }.toSet())
        assertNotSame(base, shuffled)
        // Base order untouched: off restores it as-is.
        assertEquals((0 until 10).map { "url-$it" }, base.map { it.url })
    }

    @Test
    fun persistWindowPrependsPredecessorsAndOffsetsIndex() {
        val source = list(200)
        val window = source.subList(50, 110)
        val (combined, idx) = QueuePolicy.persistQueueWindow(source, 50, window, 5)
        assertEquals(68, combined.size)
        assertEquals(source[42], combined.first())
        assertEquals(13, idx)
    }

    @Test
    fun persistWindowOnShortSourceKeepsWindow() {
        val source = list(10)
        val (combined, idx) = QueuePolicy.persistQueueWindow(source, 0, source, 3)
        assertEquals(source, combined)
        assertEquals(3, idx)
    }
}
