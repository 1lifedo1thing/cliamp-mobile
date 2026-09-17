package stream.cliamp.mobile.playback

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.ui.screens.queueEntries

/** Checks navigation selection against the same entries rendered by Up next, without audio I/O. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class QueueNavigationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: PlayerConnection
    private val tracks = List(5) {
        Station("queue-test-$it", "Track $it", "file:///queue-test-$it.wav", StationSource.Local)
    }

    @Before fun setUp() = onMain {
        player = PlayerConnection(InstrumentationRegistry.getInstrumentation().targetContext, scope)
    }

    @After fun tearDown() = onMain {
        scope.cancel()
        PlaybackBus.publishStation(null)
        PlaybackBus.publishSource(emptyList())
    }

    private fun onMain(action: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitCurrent(station: Station) = runBlocking {
        withTimeout(5_000) {
            while (!withContext(Dispatchers.Main) { PlaybackBus.station.value?.id == station.id }) delay(10)
        }
    }

    private fun nextMatchesTop() {
        lateinit var expected: Station
        onMain {
            expected = queueEntries(player.currentQueue, player.queueIndex.value).first().station
            player.next()
        }
        awaitCurrent(expected)
    }

    private fun upcoming() = queueEntries(player.currentQueue, player.queueIndex.value).map { it.station }

    @Test fun nextSelectsTheFirstVisibleUpcomingTrack() {
        onMain { player.play(tracks[2], tracks) }
        nextMatchesTop()
        onMain { assertEquals(tracks[3], PlaybackBus.station.value) }
        nextMatchesTop()
        onMain { assertEquals(tracks[4], PlaybackBus.station.value) }
    }

    @Test fun nextUsesReorderedQueueAfterPreviousInsteadOfHistoryRedo() {
        onMain { player.play(tracks[1], tracks) }
        nextMatchesTop()
        onMain { player.prev() }
        awaitCurrent(tracks[1])
        onMain { player.reorderQueue(4, 2) }
        nextMatchesTop()
        onMain { assertEquals(tracks[4], PlaybackBus.station.value) }
    }

    @Test fun nextUsesQueueAfterRemovingHistoryRedoTrack() {
        onMain { player.play(tracks[1], tracks) }
        nextMatchesTop()
        onMain { player.prev() }
        awaitCurrent(tracks[1])
        onMain { player.removeFromQueue(2) }
        nextMatchesTop()
        onMain { assertEquals(tracks[3], PlaybackBus.station.value) }
    }

    @Test fun playingAnotherSongRebuildsUpNextFromTheListOrder() {
        onMain {
            player.play(tracks[0], tracks)
            player.reorderQueue(3, 1)
            assertEquals(listOf(tracks[3], tracks[1], tracks[2], tracks[4]), upcoming())
            // Tapping the list again starts it at the tapped item; the arranged
            // tail is replaced by the list's own continuation.
            player.play(tracks[2], tracks)
            assertEquals(tracks[2], PlaybackBus.station.value)
            assertEquals(tracks.drop(3), upcoming())
            assertEquals(tracks.drop(3), player.upcomingStations())
        }
        for (expected in listOf(tracks[3], tracks[4])) {
            nextMatchesTop()
            onMain { assertEquals(expected, PlaybackBus.station.value) }
        }
        onMain { assertEquals(emptyList<Station>(), upcoming()) }
    }

    @Test fun listTapDropsManualInsertionsAndRemovals() {
        val extra = tracks[0].copy(id = "extra", url = "file:///extra.wav")
        onMain {
            player.play(tracks[0], tracks)
            player.removeFromQueue(2)
            player.playNext(extra)
            player.addToQueue(extra)
            player.play(tracks[3], tracks)
            assertEquals(tracks, PlaybackBus.source.value)
            assertEquals(listOf(tracks[4]), upcoming())
            assertTrue(player.currentQueue.none { it.id == extra.id })
        }
    }

    @Test fun returningToAListAlwaysStartsItsOrderAgain() {
        onMain {
            player.play(tracks[0], tracks)
            player.reorderQueue(4, 1)
            // A different list (same songs, reversed) replaces the arrangement.
            player.play(tracks[2], tracks.reversed())
            assertEquals(tracks.reversed().drop(3), upcoming())
            // Returning to the first list starts it afresh from the tapped song.
            player.play(tracks[0], tracks)
            assertEquals(tracks.drop(1), upcoming())
        }
    }

    @Test fun queueTapJumpsToTheOccurrenceAndKeepsTheRestUpcoming() {
        onMain {
            player.play(tracks[0], tracks)
            player.reorderQueue(4, 1)
            assertEquals(listOf(tracks[4], tracks[1], tracks[2], tracks[3]), upcoming())
            player.playQueueEntry(3)
        }
        awaitCurrent(tracks[2])
        onMain {
            assertEquals(3, player.queueIndex.value)
            assertEquals(listOf(tracks[3]), upcoming())
        }
        nextMatchesTop()
        onMain { assertEquals(tracks[3], PlaybackBus.station.value) }
    }

    @Test fun insertionsPersistUntilTheNextListTap() {
        val extra = tracks[1]
        onMain {
            player.play(tracks[0], tracks)
            player.playNext(extra)
            player.addToQueue(extra)
            val arranged = upcoming()
            assertEquals(listOf(extra, tracks[1], tracks[2], tracks[3], tracks[4], extra), arranged)
            player.play(tracks[0], tracks)
            assertEquals(tracks.drop(1), upcoming())
        }
    }

    @Test fun clearKeepsCurrentAndALaterListTapRepopulates() {
        onMain {
            player.play(tracks[0], tracks)
            player.clearQueue()
            assertEquals(tracks[0], PlaybackBus.station.value)
            assertEquals(emptyList<Station>(), upcoming())
            assertEquals(emptyList<Station>(), player.upcomingStations())
            player.play(tracks[3], tracks)
            assertEquals(tracks[3], PlaybackBus.station.value)
            assertEquals(listOf(tracks[4]), upcoming())
        }
    }

    @Test fun editingALongListKeepsItsUnloadedTailAndAListTapResetsIt() {
        val long = List(90) { tracks[0].copy(id = "long-$it", url = "file:///long-$it.wav") }
        val extra = tracks[1]
        onMain {
            player.play(long[0], long)
            player.reorderQueue(4, 1)
            player.removeFromQueue(3)
            player.addToQueue(extra)
            val expected = long.toMutableList().apply {
                add(1, removeAt(4))
                removeAt(3)
                add(extra)
            }
            assertEquals(expected, PlaybackBus.source.value)
            player.play(long[20], long)
            assertEquals(long, PlaybackBus.source.value)
            player.clearQueue()
            assertEquals(listOf(long[20]), PlaybackBus.source.value)
        }
    }

    @Test fun shuffleBuildsAFreshOrderHeadedByTheTappedTrack() {
        onMain {
            player.toggleShuffle()
            player.play(tracks[0], tracks)
            assertEquals(tracks[0], PlaybackBus.station.value)
            assertEquals(tracks[0], player.currentQueue.first())
            assertEquals(tracks.toSet(), player.currentQueue.toSet())

            // A later tap re-shuffles the same list around the tapped song
            // instead of riding the first arrangement.
            player.play(tracks[3], tracks)
            assertEquals(tracks[3], PlaybackBus.station.value)
            assertEquals(tracks[3], player.currentQueue.first())
            assertEquals(tracks.toSet(), player.currentQueue.toSet())
            assertTrue(player.shuffle.value)
        }
    }
}
