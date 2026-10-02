package stream.kleeamp.mobile.chrome

import org.junit.Assert.assertEquals
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.podcasts.DownloadQueueItem
import stream.kleeamp.mobile.podcasts.DownloadState

class DownloadQueuePillTest {
    private fun item(url: String, state: DownloadState) = DownloadQueueItem(
        url = url,
        station = Station(id = url, name = url, url = url, source = StationSource.Podcast),
        state = state,
        auto = false,
    )

    @Test fun emptyRollsUpUnknown() {
        assertEquals(-1f, queueFraction(emptyList()))
    }

    @Test fun meanOfDeterminateRows() {
        val items = listOf(
            item("a", DownloadState.Active(0.5f, 10L, 20L)),
            item("b", DownloadState.Active(1.0f, 20L, 20L)),
            item("c", DownloadState.Queued),
        )
        assertEquals(0.75f, queueFraction(items))
    }

    @Test fun indeterminateRowsDoNotDragTheMean() {
        val items = listOf(
            item("a", DownloadState.Active(-1f, 900L, -1L)),
            item("b", DownloadState.Active(0.4f, 4L, 10L)),
        )
        assertEquals(0.4f, queueFraction(items))
    }

    @Test fun allUnknownRollsUpUnknown() {
        val items = listOf(
            item("a", DownloadState.Active(-1f, 900L, -1L)),
            item("f", DownloadState.Failed("http 500")),
        )
        assertEquals(-1f, queueFraction(items))
    }
}
