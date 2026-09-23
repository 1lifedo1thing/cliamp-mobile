package stream.kleeamp.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

class QueueHistoryTest {
    private fun station(i: Int) = Station(
        id = "id-$i",
        name = "s$i",
        url = "url-$i",
        source = StationSource.Local,
    )

    @Test
    fun recordAppendsAndDedupsTip() {
        val history = QueueHistory()
        history.record(station(1))
        history.record(station(1))
        assertEquals(1, history.size)
        assertEquals(0, history.index)
        history.record(station(2))
        assertEquals(2, history.size)
        assertEquals(1, history.index)
    }

    @Test
    fun freshPlayAfterSteppingBackForksRedoTail() {
        val history = QueueHistory()
        history.record(station(1))
        history.record(station(2))
        history.record(station(3))
        assertEquals(station(2), history.back())
        history.record(station(9))
        assertEquals(3, history.size)
        assertNull(history.forward())
        // Forked: stepping back returns the item before the fork.
        assertEquals(station(2), history.back())
    }

    @Test
    fun forwardAndBackWalkEnds() {
        val history = QueueHistory()
        assertNull(history.forward())
        assertNull(history.back())
        history.record(station(1))
        assertNull(history.forward())
        assertNull(history.back())
    }

    @Test
    fun gotoTargetFindsFirstOccurrenceInLiveSource() {
        val history = QueueHistory()
        val source = listOf(station(1), station(2), station(1))
        assertEquals(0, history.gotoTarget(station(1), source, emptyList()))
    }

    @Test
    fun gotoTargetMissesForeignContext() {
        val history = QueueHistory()
        assertNull(history.gotoTarget(station(9), listOf(station(1)), emptyList()))
    }

    @Test
    fun gotoTargetNeedsLiveSource() {
        val history = QueueHistory()
        // Fallback-only walk has no occurrence to jump to: play it fresh.
        assertNull(history.gotoTarget(station(1), emptyList(), listOf(station(1))))
    }
}
