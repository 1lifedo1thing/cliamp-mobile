package stream.kleeamp.mobile.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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
    fun resolveHerePrefersPendingThenModelThenLiveThenBus() {
        assertEquals(5, QueuePolicy.resolveHere(5, 4, 3, 2))
        assertEquals(4, QueuePolicy.resolveHere(null, 4, 3, 2))
        assertEquals(3, QueuePolicy.resolveHere(null, null, 3, 2))
        assertEquals(2, QueuePolicy.resolveHere(null, null, null, 2))
        assertEquals(0, QueuePolicy.resolveHere(null, null, null, null))
    }

    @Test
    fun stepModeSeparatesColdRingAndLinear() {
        assertEquals(
            QueuePolicy.StepMode.Cold(3),
            QueuePolicy.stepMode(sourceEmpty = true, hasPending = false, ringFallback = false, shown = 3),
        )
        assertEquals(
            QueuePolicy.StepMode.Ring,
            QueuePolicy.stepMode(sourceEmpty = true, hasPending = true, ringFallback = false, shown = -1),
        )
        assertEquals(
            QueuePolicy.StepMode.Ring,
            QueuePolicy.stepMode(sourceEmpty = false, hasPending = false, ringFallback = true, shown = -1),
        )
        assertEquals(
            QueuePolicy.StepMode.Linear,
            QueuePolicy.stepMode(sourceEmpty = false, hasPending = false, ringFallback = false, shown = -1),
        )
    }

    @Test
    fun stepTargetColdWalksFromShownOrHead() {
        assertEquals(4, QueuePolicy.stepTarget(QueuePolicy.StepMode.Cold(3), 10, here = 0, delta = 1))
        // Unknown shown item starts at the head, clamped into range.
        assertEquals(0, QueuePolicy.stepTarget(QueuePolicy.StepMode.Cold(-1), 10, here = 5, delta = -9))
        assertEquals(9, QueuePolicy.stepTarget(QueuePolicy.StepMode.Cold(-1), 10, here = 5, delta = 99))
    }

    @Test
    fun stepTargetRingWrapsBothDirections() {
        assertEquals(0, QueuePolicy.stepTarget(QueuePolicy.StepMode.Ring, 6, here = 5, delta = 1))
        assertEquals(5, QueuePolicy.stepTarget(QueuePolicy.StepMode.Ring, 6, here = 0, delta = -1))
        assertEquals(2, QueuePolicy.stepTarget(QueuePolicy.StepMode.Ring, 6, here = 0, delta = 2))
    }

    @Test
    fun stepTargetLinearClampsBothEnds() {
        assertEquals(9, QueuePolicy.stepTarget(QueuePolicy.StepMode.Linear, 10, here = 9, delta = 1))
        assertEquals(0, QueuePolicy.stepTarget(QueuePolicy.StepMode.Linear, 10, here = 0, delta = -1))
        assertEquals(4, QueuePolicy.stepTarget(QueuePolicy.StepMode.Linear, 10, here = 3, delta = 1))
    }

    @Test
    fun navAvailabilityRingOpensBothDirections() {
        val nav = QueuePolicy.navAvailability(
            source = emptyList(),
            fallback = list(4),
            ringFallback = false,
            absoluteIndex = -1,
            busHere = 0,
            trail = QueuePolicy.Trail(size = 0, index = -1),
        )
        assertTrue(nav.hasPrev)
        assertTrue(nav.hasNext)
    }

    @Test
    fun navAvailabilityLinearReadsListAndPast() {
        val atEnd = QueuePolicy.navAvailability(
            source = list(4),
            fallback = emptyList(),
            ringFallback = false,
            absoluteIndex = 3,
            busHere = null,
            trail = QueuePolicy.Trail(size = 0, index = -1),
        )
        assertTrue(atEnd.hasPrev)
        assertFalse(atEnd.hasNext)

        val coldPast = QueuePolicy.navAvailability(
            source = emptyList(),
            fallback = listOf(list(4).first()),
            ringFallback = false,
            absoluteIndex = -1,
            busHere = 0,
            trail = QueuePolicy.Trail(size = 3, index = 2),
        )
        // Single-item fallback is not a ring; prev comes from the heard trail,
        // and nothing is redoable past its tip.
        assertTrue(coldPast.hasPrev)
        assertFalse(coldPast.hasNext)
    }

    @Test
    fun upcomingSliceFollowsRingOrFiniteTail() {
        val source = list(6)
        assertEquals(
            listOf(source[1], source[2], source[3]),
            QueuePolicy.upcomingSlice(source, absoluteIndex = 0, ringFallback = false, count = 3),
        )
        // Ring wraps past the end through the same helper the widget uses.
        assertEquals(3, QueuePolicy.upcomingSlice(source, absoluteIndex = 5, ringFallback = true, count = 3).size)
        assertEquals(
            emptyList<Station>(),
            QueuePolicy.upcomingSlice(source, absoluteIndex = 9, ringFallback = false),
        )
    }

    @Test
    fun widgetWindowsWrapShortSources() {
        // The 6-item crash case: anchor 0 with 8 predecessors floors into range.
        val source = list(6)
        val windows = QueuePolicy.widgetWindowIndices(
            source = source,
            windowBase = 0,
            upNextIndex = 0,
            stationUrl = "url-0",
            ringFallback = false,
        )
        assertEquals(17, windows!!.window.size)
        assertEquals(4, windows.next.size)
        assertEquals("url-1", windows.next.first().url)
    }

    @Test
    fun widgetWindowsMissReturnsNull() {
        assertNull(
            QueuePolicy.widgetWindowIndices(
                source = list(4),
                windowBase = 0,
                upNextIndex = 0,
                stationUrl = "nope",
                ringFallback = false,
            )
        )
        assertNull(
            QueuePolicy.widgetWindowIndices(
                source = emptyList(),
                windowBase = 0,
                upNextIndex = 0,
                stationUrl = "url-0",
                ringFallback = false,
            )
        )
    }

    @Test
    fun shuffleReorderKeepsAnchorAndElements() {
        val base = list(10)
        val anchor = base[4]
        val reordered = QueuePolicy.shuffleReorder(base, anchorIndex = 4, anchor = anchor)
        assertEquals(anchor, reordered[4])
        assertEquals(base.map { it.url }.toSet(), reordered.map { it.url }.toSet())
        assertNotSame(base, reordered)
        // Base order untouched: toggling off restores it as-is.
        assertEquals((0 until 10).map { "url-$it" }, base.map { it.url })
    }

    @Test
    fun shuffleAnchorPrefersLiveAudibleOverModelIndex() {
        val source = list(10)
        // Model lags at 2 while Media3 audibly plays 5: the rebuild anchors 5.
        val anchor = QueuePolicy.shuffleAnchor(
            source = source,
            base = source,
            windowBase = 0,
            currentIndex = 2,
            upNextFirst = source[0],
            audibleId = "id-5",
        )
        assertEquals(source[5], anchor.current)
        assertEquals(5, anchor.baseIndex)
    }

    @Test
    fun shuffleAnchorFallsBackToModelOccurrence() {
        val source = list(10)
        // No live anchor (null id): the model occurrence wins, never a
        // first-URL match that would rewind duplicate songs.
        val anchor = QueuePolicy.shuffleAnchor(
            source = source,
            base = source,
            windowBase = 0,
            currentIndex = 3,
            upNextFirst = source[0],
            audibleId = null,
        )
        assertEquals(source[3], anchor.current)
        assertEquals(3, anchor.baseIndex)
    }

    @Test
    fun singleItemIndexReadsModelThenSearchesById() {
        val q = list(4)
        assertEquals(2, QueuePolicy.singleItemIndex(q, modelIndex = 2, curId = "id-2"))
        assertEquals(1, QueuePolicy.singleItemIndex(q, modelIndex = 3, curId = "id-1"))
        assertNull(QueuePolicy.singleItemIndex(q, modelIndex = 3, curId = "id-9"))
    }

    @Test
    fun shouldExtendOnlyNearTailOfLongerSource() {
        fun extend(oneToOne: Boolean, base: Int, size: Int, source: Int, index: Int) =
            QueuePolicy.shouldExtend(oneToOne, base, size, source, index)
        assertTrue(extend(oneToOne = true, base = 0, size = 60, source = 200, index = 58))
        assertFalse(extend(oneToOne = true, base = 0, size = 60, source = 200, index = 10))
        assertFalse(extend(oneToOne = true, base = 0, size = 60, source = 60, index = 58))
        assertFalse(extend(oneToOne = false, base = 0, size = 60, source = 200, index = 58))
    }
}
