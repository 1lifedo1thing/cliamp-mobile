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
}
