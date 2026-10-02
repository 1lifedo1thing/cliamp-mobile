package stream.kleeamp.mobile.podcasts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

class DownloadQueueTest {
    private fun station(url: String) = Station(
        id = "pod:test:$url",
        name = url,
        url = url,
        source = StationSource.Podcast,
    )

    private fun item(url: String, state: DownloadState, auto: Boolean = false) =
        DownloadQueueItem(url, station(url), state, auto)

    @Test fun queueOrdersActiveQueuedFailed() {
        val ordered = orderQueueItems(
            listOf(
                item("f", DownloadState.Failed("http 500")),
                item("q", DownloadState.Queued),
                item("a", DownloadState.Active(0.5f, 10L, 20L)),
            ),
        ).map { it.url }
        assertEquals(listOf("a", "q", "f"), ordered)
    }

    @Test fun queueKeepsArrivalOrderWithinAState() {
        val ordered = orderQueueItems(
            listOf(
                item("q2", DownloadState.Queued),
                item("a2", DownloadState.Active(0.1f, 1L, 10L)),
                item("q1", DownloadState.Queued),
                item("a1", DownloadState.Active(0.9f, 9L, 10L)),
            ),
        ).map { it.url }
        assertEquals(listOf("a2", "a1", "q2", "q1"), ordered)
    }

    @Test fun cleanupScopeDefaultsOff() {
        assertEquals(CleanupScope.Off, CleanupScope.of(""))
        assertEquals(CleanupScope.Off, CleanupScope.of("everything"))
        assertEquals(CleanupScope.Auto, CleanupScope.of("auto"))
        assertEquals(CleanupScope.All, CleanupScope.of("all"))
    }

    @Test fun cleanupScopeCoversManualOnlyWhenAll() {
        assertFalse(CleanupScope.Off.covers(auto = true))
        assertFalse(CleanupScope.Off.covers(auto = false))
        assertTrue(CleanupScope.Auto.covers(auto = true))
        assertFalse(CleanupScope.Auto.covers(auto = false))
        assertTrue(CleanupScope.All.covers(auto = true))
        assertTrue(CleanupScope.All.covers(auto = false))
    }
}
