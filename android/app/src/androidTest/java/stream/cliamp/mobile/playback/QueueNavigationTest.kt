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
    private val playlist = PlaybackContext.Playlist("my-playlist")
    private fun upcoming() = queueEntries(player.currentQueue, player.queueIndex.value).map { it.station }

    @Test fun samePlaylistPreservesReorderingAndKeepsSelectedSongPending() {
        onMain {
            player.playFromList(tracks[0], tracks, playlist)
            player.reorderQueue(3, 1)
            assertEquals(listOf(tracks[3], tracks[1], tracks[2], tracks[4]), upcoming())
            assertEquals(upcoming(), player.upcomingStations())
            player.playFromList(tracks[2], tracks, playlist)
            assertEquals(tracks[2], PlaybackBus.station.value)
            assertEquals(listOf(tracks[3], tracks[1], tracks[2], tracks[4]), upcoming())
            assertEquals(upcoming(), player.upcomingStations())
        }
        for (expected in listOf(tracks[3], tracks[1], tracks[2], tracks[4])) {
            nextMatchesTop()
            onMain { assertEquals(expected, PlaybackBus.station.value) }
        }
        onMain { assertEquals(emptyList<Station>(), upcoming()) }
    }

    @Test fun sameIdentitySurvivesSortRefreshRemovalAndAddedItems() {
        val extra = tracks[0].copy(id = "extra", url = "file:///extra.wav")
        onMain {
            player.playFromList(tracks[0], tracks, playlist)
            player.removeFromQueue(2)
            player.playNext(extra)
            val arranged = upcoming()
            player.playFromList(tracks[2], tracks.reversed(), PlaybackContext.Playlist("my-playlist"))
            assertEquals(arranged, upcoming())
            player.playFromList(tracks[2], tracks, playlist)
            assertEquals(arranged, upcoming())
        }
        nextMatchesTop()
        onMain { assertEquals(extra, PlaybackBus.station.value) }
    }

    @Test fun differentListReplacesEvenWithIdenticalContentsAndReturningDoesNotRestoreOldOrder() {
        val second = PlaybackContext.Playlist("second")
        onMain {
            player.playFromList(tracks[0], tracks, playlist)
            player.reorderQueue(4, 1)
            player.playFromList(tracks[2], tracks, second)
            assertEquals(tracks.drop(3), upcoming())
            player.playFromList(tracks[0], tracks, playlist)
            assertEquals(tracks.drop(1), upcoming())
        }
    }

    @Test fun podcastSwitchReplacesMusicButEpisodeInsertionKeepsMusicContext() {
        val episodes = tracks.map { it.copy(id = "pod-${it.id}", url = "file:///pod-${it.id}.wav", source = StationSource.Podcast) }
        val show = PlaybackContext.Podcast("https://example.test/show.xml")
        onMain {
            player.playFromList(tracks[0], tracks, playlist)
            player.reorderQueue(4, 1)
            player.playNext(episodes[0])
            player.addToQueue(episodes[1])
            val arranged = upcoming()
            player.playFromList(tracks[2], tracks, playlist)
            assertEquals(arranged, upcoming())
            player.playFromList(episodes[1], episodes, show)
            assertEquals(episodes.drop(2), upcoming())
            player.reorderQueue(4, 2)
            val episodeOrder = upcoming()
            player.playFromList(episodes[3], episodes, show)
            assertEquals(episodeOrder, upcoming())
            player.playFromList(tracks[1], tracks, playlist)
            assertEquals(tracks.drop(2), upcoming())
        }
    }

    @Test fun distinctAlbumsAccountsFoldersShowsAndRadioListsStartFresh() {
        val contexts = listOf(
            PlaybackContext.ProviderAlbum("account-a", "album-a"),
            PlaybackContext.ProviderAlbum("account-a", "album-b"),
            PlaybackContext.ProviderAlbum("account-b", "album-b"),
            PlaybackContext.ProviderSongs("account-b"),
            PlaybackContext.Library("local", "folder-a"),
            PlaybackContext.Library("local", "folder-b"),
            PlaybackContext.Podcast("https://example.test/a.xml"),
            PlaybackContext.Podcast("https://example.test/b.xml"),
            PlaybackContext.CliampRadio,
            PlaybackContext.CustomRadio,
            PlaybackContext.Search("one", "All"),
            PlaybackContext.Search("two", "All"),
        )
        onMain {
            contexts.forEach { context ->
                player.playFromList(tracks[0], tracks, context)
                assertEquals(tracks.drop(1), upcoming())
                player.reorderQueue(4, 1)
                val arranged = upcoming()
                player.playFromList(tracks[2], tracks, context)
                assertEquals(arranged, upcoming())
            }
        }
    }

    @Test fun radioNextUsesArrangedOrderWithRepeatedStation() {
        val radio = tracks.map { it.copy(source = StationSource.Custom) }
        onMain {
            player.playFromList(radio[0], radio, PlaybackContext.CustomRadio)
            player.reorderQueue(4, 1)
            player.playFromList(radio[2], radio, PlaybackContext.CustomRadio)
            assertEquals(listOf(radio[4], radio[1], radio[2], radio[3]), upcoming())
        }
        nextMatchesTop()
        onMain { assertEquals(radio[4], PlaybackBus.station.value) }
    }

    @Test fun queueTapSelectsAnOccurrenceAndDoesNotChangeTheOriginatingList() {
        onMain {
            player.playFromList(tracks[0], tracks, playlist)
            player.playFromList(tracks[2], tracks, playlist)
            // [A, C (current), B, C, D, E]: select the pending C explicitly.
            player.playQueueEntry(3)
        }
        awaitCurrent(tracks[2])
        onMain {
            assertEquals(3, player.queueIndex.value)
            assertEquals(tracks.drop(3), upcoming())
            player.playFromList(tracks[1], tracks, playlist)
            assertEquals(tracks.drop(3), upcoming())
        }
        nextMatchesTop()
        onMain { assertEquals(tracks[3], PlaybackBus.station.value) }
    }

    @Test fun clearKeepsCurrentAndSameListTapDoesNotRepopulate() {
        onMain {
            player.playFromList(tracks[0], tracks, playlist)
            player.clearQueue()
            assertEquals(tracks[0], PlaybackBus.station.value)
            assertEquals(emptyList<Station>(), upcoming())
            assertEquals(emptyList<Station>(), player.upcomingStations())
            player.playFromList(tracks[3], tracks, playlist)
            assertEquals(emptyList<Station>(), upcoming())
            player.next()
            assertEquals(tracks[3], PlaybackBus.station.value)
            player.playFromList(tracks[1], tracks, PlaybackContext.Playlist("other"))
            assertEquals(tracks.drop(2), upcoming())
        }
    }

    @Test fun editingALongListKeepsItsUnloadedTailAndAppendGoesToTheEnd() {
        val long = List(90) { tracks[0].copy(id = "long-$it", url = "file:///long-$it.wav") }
        val extra = tracks[1]
        onMain {
            player.playFromList(long[0], long, playlist)
            player.reorderQueue(4, 1)
            player.removeFromQueue(3)
            player.addToQueue(extra)
            player.playFromList(long[20], long, playlist)
            val expected = long.toMutableList().apply { add(1, removeAt(4)); removeAt(3); add(extra); add(1, long[20]) }
            assertEquals(expected, PlaybackBus.source.value)
            player.clearQueue()
            assertEquals(listOf(long[20]), PlaybackBus.source.value)
        }
    }

    @Test fun shuffleDoesNotReshuffleASameSourceTap() {
        onMain {
            player.toggleShuffle()
            player.playFromList(tracks[0], tracks, playlist)
            val arranged = upcoming()
            player.playFromList(tracks[3], tracks, playlist)
            assertEquals(arranged, upcoming())
        }
    }

}
