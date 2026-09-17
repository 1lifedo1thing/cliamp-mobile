package stream.kleeamp.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import stream.kleeamp.mobile.data.Station
import stream.kleeamp.mobile.data.StationSource
import stream.kleeamp.mobile.playback.upNextIndices

class UpNextEntriesTest {
    private val upNext = List(5) { Station("$it", "Track $it", "file:///$it.wav", StationSource.Local) }

    @Test fun middleTrackShowsOnlyItsSuccessors() {
        val upcoming = upNextEntries(upNext, 2)
        assertEquals(listOf("3", "4"), upcoming.map { it.station.id })
        assertEquals(listOf(3, 4), upcoming.map { it.upNextIndex })
        assertEquals(upcoming.size, upNextIndices(upNext.size, 2).count())
    }

    @Test fun lastTrackHasNothingUpNext() {
        assertEquals(emptyList<UpNextEntry>(), upNextEntries(upNext, upNext.lastIndex))
        assertEquals(0, upNextIndices(upNext.size, upNext.lastIndex).count())
    }

    @Test fun unknownCurrentKeepsTheWholePendingUpNext() {
        assertEquals(upNext, upNextEntries(upNext, -1).map { it.station })
        assertEquals(emptyList<UpNextEntry>(), upNextEntries(emptyList(), -1))
    }

    @Test fun repeatedTracksKeepOccurrenceKeysAsPlaybackAdvances() {
        val repeated = listOf(upNext[0], upNext[1], upNext[0], upNext[2])
        val before = upNextEntries(repeated, 0)
        val after = upNextEntries(repeated, 1)
        assertEquals(before.drop(1), after)
        assertEquals(2, after.first().upNextIndex)
        assertEquals("file:///0.wav#1", after.first().key)
    }
}
