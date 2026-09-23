package stream.kleeamp.mobile.playback

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
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.player.upNextEntries

/** Checks navigation selection against the same entries rendered by Up next, without audio I/O. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class UpNextNavigationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: PlayerConnection
    private val tracks = List(5) {
        Station("upnext-test-$it", "Track $it", "file:///upnext-test-$it.wav", StationSource.Local)
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
            expected = upNextEntries(player.currentUpNext, player.upNextIndex.value).first().station
            player.next()
        }
        awaitCurrent(expected)
    }

    private fun upcoming() = upNextEntries(player.currentUpNext, player.upNextIndex.value).map { it.station }

    @Test fun prevPastFirstTappedKeepsWalkingBackwardAndUpNextFollows() {
        onMain { player.play(tracks[2], tracks) }
        nextMatchesTop()
        nextMatchesTop()
        onMain { assertEquals(tracks[4], PlaybackBus.station.value) }
        // Back through everything heard: 4 -> 3 -> 2.
        onMain { player.prev() }
        awaitCurrent(tracks[3])
        onMain { player.prev() }
        awaitCurrent(tracks[2])
        // Past the first tapped song the walk continues into earlier items
        // and Up Next tracks the new position: 2 -> 1 -> 0, then it holds.
        onMain { player.prev() }
        awaitCurrent(tracks[1])
        onMain {
            assertEquals(1, player.upNextIndex.value)
            assertEquals(tracks.drop(2), upcoming())
        }
        onMain { player.prev() }
        awaitCurrent(tracks[0])
        onMain {
            assertEquals(0, player.upNextIndex.value)
            assertEquals(tracks.drop(1), upcoming())
        }
        onMain { player.prev() }
        awaitCurrent(tracks[0])
        onMain {
            assertEquals(0, player.upNextIndex.value)
            assertEquals(tracks.drop(1), upcoming())
        }
    }

    @Test fun nextSelectsTheFirstVisibleUpcomingTrack() {
        onMain { player.play(tracks[2], tracks) }
        nextMatchesTop()
        onMain { assertEquals(tracks[3], PlaybackBus.station.value) }
        nextMatchesTop()
        onMain { assertEquals(tracks[4], PlaybackBus.station.value) }
    }

    @Test fun nextUsesReorderedUpNextAfterPreviousInsteadOfHistoryRedo() {
        onMain { player.play(tracks[1], tracks) }
        nextMatchesTop()
        onMain { player.prev() }
        awaitCurrent(tracks[1])
        onMain { player.reorderUpNext(4, 2) }
        nextMatchesTop()
        onMain { assertEquals(tracks[4], PlaybackBus.station.value) }
    }

    @Test fun nextUsesUpNextAfterRemovingHistoryRedoTrack() {
        onMain { player.play(tracks[1], tracks) }
        nextMatchesTop()
        onMain { player.prev() }
        awaitCurrent(tracks[1])
        onMain { player.removeFromUpNext(2) }
        nextMatchesTop()
        onMain { assertEquals(tracks[3], PlaybackBus.station.value) }
    }

    @Test fun playingAnotherSongRebuildsUpNextFromTheListOrder() {
        onMain {
            player.play(tracks[0], tracks)
            player.reorderUpNext(3, 1)
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
            player.removeFromUpNext(2)
            player.playNext(extra)
            player.addToUpNext(extra)
            player.play(tracks[3], tracks)
            assertEquals(tracks, PlaybackBus.source.value)
            assertEquals(listOf(tracks[4]), upcoming())
            assertTrue(player.currentUpNext.none { it.id == extra.id })
        }
    }

    @Test fun returningToAListAlwaysStartsItsOrderAgain() {
        onMain {
            player.play(tracks[0], tracks)
            player.reorderUpNext(4, 1)
            // A different list (same songs, reversed) replaces the arrangement.
            player.play(tracks[2], tracks.reversed())
            assertEquals(tracks.reversed().drop(3), upcoming())
            // Returning to the first list starts it afresh from the tapped song.
            player.play(tracks[0], tracks)
            assertEquals(tracks.drop(1), upcoming())
        }
    }

    @Test fun upNextTapJumpsToTheOccurrenceAndKeepsTheRestUpcoming() {
        onMain {
            player.play(tracks[0], tracks)
            player.reorderUpNext(4, 1)
            assertEquals(listOf(tracks[4], tracks[1], tracks[2], tracks[3]), upcoming())
            player.playUpNextEntry(3)
        }
        awaitCurrent(tracks[2])
        onMain {
            assertEquals(3, player.upNextIndex.value)
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
            player.addToUpNext(extra)
            val arranged = upcoming()
            assertEquals(listOf(extra, tracks[1], tracks[2], tracks[3], tracks[4], extra), arranged)
            player.play(tracks[0], tracks)
            assertEquals(tracks.drop(1), upcoming())
        }
    }

    @Test fun clearKeepsCurrentAndALaterListTapRepopulates() {
        onMain {
            player.play(tracks[0], tracks)
            player.clearUpNext()
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
            player.reorderUpNext(4, 1)
            player.removeFromUpNext(3)
            player.addToUpNext(extra)
            val expected = long.toMutableList().apply {
                add(1, removeAt(4))
                removeAt(3)
                add(extra)
            }
            assertEquals(expected, PlaybackBus.source.value)
            player.play(long[20], long)
            assertEquals(long, PlaybackBus.source.value)
            player.clearUpNext()
            assertEquals(listOf(long[20]), PlaybackBus.source.value)
        }
    }

    @Test fun shuffleBuildsAFreshOrderHeadedByTheTappedTrack() {
        onMain {
            player.toggleShuffle()
            player.play(tracks[0], tracks)
            assertEquals(tracks[0], PlaybackBus.station.value)
            assertEquals(tracks[0], player.currentUpNext.first())
            assertEquals(tracks.toSet(), player.currentUpNext.toSet())

            // A later tap re-shuffles the same list around the tapped song
            // instead of riding the first arrangement.
            player.play(tracks[3], tracks)
            assertEquals(tracks[3], PlaybackBus.station.value)
            assertEquals(tracks[3], player.currentUpNext.first())
            assertEquals(tracks.toSet(), player.currentUpNext.toSet())
            assertTrue(player.shuffle.value)
        }
    }
}
