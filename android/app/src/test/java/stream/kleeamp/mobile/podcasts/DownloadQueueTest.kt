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

    private fun item(url: String, state: DownloadState, auto: Boolean = false, seq: Long = 0) =
        DownloadQueueItem(url, station(url), state, auto, seq)

    @Test fun queueFollowsQueueSequence() {
        val ordered = orderQueueItems(
            listOf(
                item("c", DownloadState.Queued, seq = 3),
                item("a", DownloadState.Active(0.5f, 10L, 20L), seq = 1),
                item("b", DownloadState.Queued, seq = 2),
            ),
        ).map { it.url }
        assertEquals(listOf("a", "b", "c"), ordered)
    }

    @Test fun queueKeepsEpisodeOrderAcrossStates() {
        val ordered = orderQueueItems(
            listOf(
                item("f", DownloadState.Failed("http 500"), seq = 4),
                item("p", DownloadState.Paused(10L, 20L), seq = 3),
                item("q", DownloadState.Queued, seq = 2),
                item("a", DownloadState.Active(0.5f, 10L, 20L), seq = 1),
            ),
        ).map { it.url }
        assertEquals(listOf("a", "q", "p", "f"), ordered)
    }

    @Test fun queueSequenceBeatsArrivalOrder() {
        val ordered = orderQueueItems(
            listOf(
                item("q2", DownloadState.Queued, seq = 2),
                item("a2", DownloadState.Active(0.1f, 1L, 10L), seq = 4),
                item("q1", DownloadState.Queued, seq = 1),
                item("a1", DownloadState.Active(0.9f, 9L, 10L), seq = 3),
            ),
        ).map { it.url }
        assertEquals(listOf("q1", "q2", "a1", "a2"), ordered)
    }

    private fun entry(url: String, seq: Long, downloadedAt: Long = 0L) = DownloadEntry(
        url = url,
        path = "/tmp/$url",
        station = station(url),
        downloadedAt = downloadedAt,
        seq = seq,
    )

    @Test fun finishedListFollowsQueueTickets() {
        val stations = listOf("ep12", "ep2", "ep1").map { station(it) }
        val entries = mapOf(
            "ep1" to entry("ep1", seq = 1),
            "ep2" to entry("ep2", seq = 2),
            "ep12" to entry("ep12", seq = 3),
        )
        assertEquals(
            listOf("ep1", "ep2", "ep12"),
            stations.sortedByQueueOrder(entries).map { it.url },
        )
    }

    @Test fun finishedListWithoutTicketsFallsBackToCompletionTime() {
        val stations = listOf("b", "a").map { station(it) }
        val entries = mapOf(
            "a" to entry("a", seq = 0, downloadedAt = 2L),
            "b" to entry("b", seq = 0, downloadedAt = 1L),
        )
        assertEquals(
            listOf("b", "a"),
            stations.sortedByQueueOrder(entries).map { it.url },
        )
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
