package stream.cliamp.mobile.playback

import android.content.ComponentName
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
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
import stream.cliamp.mobile.CliampApp
import stream.cliamp.mobile.MainActivity
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.widget.WidgetControl

/** Real Media3 playback with local silent WAV fixtures; no remote audio dependency. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class QueuePlaybackTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val connection get() = (context.applicationContext as CliampApp).player
    private lateinit var activity: ActivityScenario<MainActivity>
    private var controller: MediaController? = null
    private val files = mutableListOf<File>()
    private val source = PlaybackContext.Playlist("playback-fixture-${UUID.randomUUID()}")

    private fun onMain(action: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitCondition(label: String = "condition", condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(20_000) {
                while (!withContext(Dispatchers.Main) { condition() }) delay(20)
            }
        } catch (error: TimeoutCancellationException) {
            val detail = withContext(Dispatchers.Main) {
                "bus=${PlaybackBus.station.value?.name}, index=${connection.queueIndex.value}, " +
                    "queue=${connection.currentQueue.size}, media=${controller?.currentMediaItem?.mediaMetadata?.title}, " +
                    "state=${controller?.playbackState}, playing=${controller?.isPlaying}, " +
                    "position=${controller?.currentPosition}, duration=${controller?.duration}, " +
                    "suppression=${controller?.playbackSuppressionReason}, error=${controller?.playerError}"
            }
            throw AssertionError("Waiting for $label: $detail", error)
        }
    }

    @Before fun setUp() {
        activity = ActivityScenario.launch(MainActivity::class.java)
        onMain {
            val future = MediaController.Builder(context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            future.addListener({ controller = future.get() }, MoreExecutors.directExecutor())
        }
        awaitCondition { controller != null }
        var ready = false
        onMain { connection.doWhenReady { ready = true } }
        awaitCondition { ready }
        onMain {
            controller!!.pause()
            controller!!.volume = 0f
            controller!!.repeatMode = Player.REPEAT_MODE_OFF
            if (connection.shuffle.value) connection.toggleShuffle()
        }
    }

    @After fun tearDown() {
        onMain {
            controller?.stop()
            controller?.clearMediaItems()
            controller?.release()
            controller = null
            connection.release()
        }
        if (::activity.isInitialized) activity.close()
        files.forEach(File::delete)
    }

    private fun track(name: String, kind: StationSource = StationSource.Local): Station {
        val id = UUID.randomUUID().toString()
        val samples = 16_000 * 30
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        val file = File(context.cacheDir, "queue-$id.wav").also { it.writeBytes(bytes.array()); files += it }
        return Station(id, name, file.toURI().toString(), kind, durationMs = 30_000)
    }

    private fun awaitPlaying(station: Station) = awaitCondition("playing ${station.name}") {
        controller?.currentMediaItem?.mediaId == station.id &&
            controller?.playbackState == Player.STATE_READY &&
            PlaybackBus.station.value?.id == station.id
    }

    private fun finishCurrent(expected: Station) {
        onMain {
            val player = controller!!
            player.seekTo(player.duration - 50)
            player.play()
        }
        awaitPlaying(expected)
    }

    @Test fun automaticAdvanceFollowsArrangedOrderIncludingTheRepeatedOccurrence() {
        val tracks = listOf("A", "B", "C", "D", "E").map { track(it) }
        onMain { connection.playFromList(tracks[0], tracks, source) }
        awaitPlaying(tracks[0])
        onMain { connection.reorderQueue(3, 1) }
        awaitCondition { controller?.getMediaItemAt(1)?.mediaId == tracks[3].id }
        onMain { connection.playFromList(tracks[2], tracks, source) }
        awaitPlaying(tracks[2])
        for (i in listOf(3, 1, 2, 4)) finishCurrent(tracks[i])
        onMain {
            assertEquals(connection.currentQueue.lastIndex, connection.queueIndex.value)
            controller!!.seekTo(controller!!.duration - 50)
        }
        awaitCondition { controller?.playbackState == Player.STATE_ENDED }
        onMain { assertEquals(tracks[4], PlaybackBus.station.value) }
    }

    @Test fun mixedMusicPodcastAndRadioUseTheSameNextOrder() {
        val music = track("Music")
        val podcast = track("Episode", StationSource.Podcast)
        val radio = track("Radio", StationSource.Custom)
        val last = track("Last")
        val tracks = listOf(music, podcast, radio, last)
        onMain { connection.playFromList(music, tracks, source) }
        awaitPlaying(music)
        finishCurrent(podcast)
        finishCurrent(radio)
        // Even a finite test response from a radio source must not auto-skip stations.
        onMain { controller!!.seekTo(controller!!.duration - 50) }
        awaitCondition { controller?.playbackState == Player.STATE_ENDED }
        onMain {
            assertEquals(radio, PlaybackBus.station.value)
            connection.next()
        }
        awaitPlaying(last)
    }

    @Test fun longListWindowExtensionKeepsTheAudibleTrackAndPosition() {
        val audio = track("Fixture")
        val tracks = List(90) { audio.copy(id = "${audio.id}-$it", name = "Track $it") }
        onMain { connection.playFromList(tracks[0], tracks, source) }
        awaitPlaying(tracks[0])
        onMain { connection.playQueueEntry(58) }
        awaitPlaying(tracks[58])
        onMain { controller!!.seekTo(5_000) }
        awaitCondition("window at Track 58") { connection.currentQueue.firstOrNull()?.id == tracks[58].id }
        awaitCondition("seek to 5 seconds") { (controller?.currentPosition ?: 0) >= 5_000 }
        onMain {
            assertEquals(tracks[58].id, controller!!.currentMediaItem?.mediaId)
            assertTrue(controller!!.currentPosition >= 5_000)
        }
        finishCurrent(tracks[59])
    }

    @Test fun rapidSameListTapsAndWidgetNextKeepTheArrangedTail() {
        val tracks = listOf("A", "B", "C", "D").map { track(it) }
        onMain { connection.playFromList(tracks[0], tracks, source) }
        awaitPlaying(tracks[0])
        onMain {
            connection.reorderQueue(3, 1)
            connection.playFromList(tracks[1], tracks, source)
            connection.playFromList(tracks[2], tracks, source)
        }
        awaitPlaying(tracks[2])
        runBlocking { WidgetControl.step(context, 1) }
        awaitPlaying(tracks[3])
        onMain { assertEquals(tracks[1].id, connection.currentQueue[connection.queueIndex.value + 1].id) }
    }

    @Test fun clearKeepsPlaybackPositionAndStopsAfterCurrentItem() {
        val tracks = listOf(track("A"), track("B"))
        onMain { connection.playFromList(tracks[0], tracks, source) }
        awaitPlaying(tracks[0])
        onMain { controller!!.seekTo(5_000) }
        awaitCondition("seek reaches app controller") { connection.state.value.positionMs >= 5_000 }
        onMain { connection.clearQueue() }
        awaitCondition { controller?.mediaItemCount == 1 }
        awaitPlaying(tracks[0])
        onMain {
            assertTrue(controller!!.currentPosition >= 5_000)
            assertEquals(tracks[0].id, controller!!.currentMediaItem?.mediaId)
            controller!!.seekTo(controller!!.duration - 50)
        }
        awaitCondition { controller?.playbackState == Player.STATE_ENDED }
        onMain { assertEquals(tracks[0], PlaybackBus.station.value) }
    }
}
