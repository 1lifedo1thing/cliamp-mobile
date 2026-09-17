package stream.cliamp.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.playback.upNextIndices

class QueueEntriesTest {
    private val queue = List(5) { Station("$it", "Track $it", "file:///$it.wav", StationSource.Local) }

    @Test fun middleTrackShowsOnlyItsSuccessors() {
        val upcoming = queueEntries(queue, 2)
        assertEquals(listOf("3", "4"), upcoming.map { it.station.id })
        assertEquals(listOf(3, 4), upcoming.map { it.queueIndex })
        assertEquals(upcoming.size, upNextIndices(queue.size, 2).count())
    }

    @Test fun lastTrackHasNothingUpNext() {
        assertEquals(emptyList<QueueEntry>(), queueEntries(queue, queue.lastIndex))
        assertEquals(0, upNextIndices(queue.size, queue.lastIndex).count())
    }

    @Test fun unknownCurrentKeepsTheWholePendingQueue() {
        assertEquals(queue, queueEntries(queue, -1).map { it.station })
        assertEquals(emptyList<QueueEntry>(), queueEntries(emptyList(), -1))
    }

    @Test fun repeatedTracksKeepOccurrenceKeysAsPlaybackAdvances() {
        val repeated = listOf(queue[0], queue[1], queue[0], queue[2])
        val before = queueEntries(repeated, 0)
        val after = queueEntries(repeated, 1)
        assertEquals(before.drop(1), after)
        assertEquals(2, after.first().queueIndex)
        assertEquals("file:///0.wav#1", after.first().key)
    }
}
