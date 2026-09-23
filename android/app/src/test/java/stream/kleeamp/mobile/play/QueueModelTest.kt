package stream.kleeamp.mobile.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

class QueueModelTest {
    private fun station(i: Int) = Station(
        id = "id-$i",
        name = "s$i",
        url = "url-$i",
        source = StationSource.Local,
    )

    private fun list(n: Int) = (0 until n).map(::station)

    @Test
    fun playFromListWindowsFromTappedIndex() {
        val model = QueueModel()
        val from = list(10)
        model.playFromList(from[4], from, preserveOrder = false, sourceIndex = null)
        assertEquals(from, model.source)
        assertEquals(from, model.baseSource)
        assertEquals(from, model.currentUpNext)
        assertEquals(4, model.currentIndex)
        assertEquals(0, model.windowBase)
        assertFalse(model.ringFallback)
    }

    @Test
    fun playFromListCapsHugeSources() {
        val model = QueueModel()
        val from = list(200)
        model.playFromList(from[100], from, preserveOrder = false, sourceIndex = null)
        assertEquals(200, model.source.size)
        assertEquals(60, model.currentUpNext.size)
        assertEquals(from[100], model.currentUpNext.first())
        assertEquals(from[159], model.currentUpNext.last())
        assertEquals(100, model.windowBase)
        assertEquals(0, model.currentIndex)
    }

    @Test
    fun playFromListRebasesShuffleBookkeeping() {
        val model = QueueModel()
        val first = list(5)
        model.playFromList(first[0], first, preserveOrder = false, sourceIndex = null)
        val second = listOf(station(9), station(8), station(7))
        model.playFromList(second[0], second, preserveOrder = false, sourceIndex = null)
        // The stale base from the first play is gone; toggling shuffle off
        // later restores the second list, not the first.
        assertEquals(second.map { it.url }, model.baseSource.map { it.url })
    }

    @Test
    fun playFromWindowSingleBecomesLoneQueue() {
        val model = QueueModel()
        val s = station(3)
        model.playFromWindow(s)
        assertEquals(listOf(s), model.source)
        assertEquals(listOf(s), model.currentUpNext)
        assertEquals(0, model.currentIndex)
    }

    @Test
    fun playFromWindowKeepsKnownWindow() {
        val model = QueueModel()
        val from = list(6)
        model.playFromList(from[0], from, preserveOrder = false, sourceIndex = null)
        model.playFromWindow(from[4])
        assertEquals(from, model.source)
        assertEquals(from, model.currentUpNext)
        assertEquals(4, model.currentIndex)
    }

    @Test
    fun rewindowSlicesHugeSource() {
        val model = QueueModel()
        model.source = list(200)
        val w = model.rewindow(190)
        assertEquals(190, model.windowBase)
        assertEquals(10, model.currentUpNext.size)
        assertEquals(0, model.currentIndex)
        assertEquals(190, w.base)
    }

    @Test
    fun replaceOrderSwapsWalkingLists() {
        val model = QueueModel()
        val base = list(4)
        model.replaceOrder(listOf(base[3], base[0]), base)
        assertEquals(listOf("url-3", "url-0"), model.source.map { it.url })
        assertEquals(base.map { it.url }, model.baseSource.map { it.url })
    }

    @Test
    fun stationForMediaIdPrefersWindowThenSource() {
        val model = QueueModel()
        model.source = list(10)
        model.rewindow(5)
        assertEquals("url-5", model.stationForMediaId("id-5")?.url)
        assertEquals("url-1", model.stationForMediaId("id-1")?.url)
        assertEquals(null, model.stationForMediaId("nope"))
    }

    @Test
    fun shuffleFlagRoundTrips() {
        val model = QueueModel()
        assertFalse(model.shuffleOn)
        model.setShuffled(true)
        assertTrue(model.shuffleOn)
        assertTrue(model.shuffle.value)
    }

    @Test
    fun fallbackSourceIsSettable() {
        val model = QueueModel()
        model.setFallbackSource(list(3))
        assertEquals(3, model.fallback.size)
    }
}
