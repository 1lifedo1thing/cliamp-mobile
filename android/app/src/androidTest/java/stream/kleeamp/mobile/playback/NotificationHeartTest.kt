package stream.kleeamp.mobile.playback

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
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
import kotlin.math.abs
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
import stream.kleeamp.mobile.KleeampApp
import stream.kleeamp.mobile.MainActivity
import stream.kleeamp.mobile.R
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

/**
 * The favourite action in the media notification must be the heart, not the
 * old star: plays a silent local fixture (real player, real session, real
 * notification) and checks the favourite action's icon. The resource id is
 * exact when the provider forwards it; otherwise the pixels are compared
 * against the heart drawable (a star fails that by a wide margin).
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class NotificationHeartTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val connection get() = (context.applicationContext as KleeampApp).player
    private lateinit var activity: ActivityScenario<MainActivity>
    private var controller: MediaController? = null
    private val files = mutableListOf<File>()

    private fun onMain(action: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitCondition(label: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(20_000) {
                while (!withContext(Dispatchers.Main) { condition() }) delay(20)
            }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError("Waiting for $label", error)
        }
    }

    @Before fun setUp() {
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
                ).close()
        }
        activity = ActivityScenario.launch(MainActivity::class.java)
        onMain {
            val future = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).buildAsync()
            future.addListener({ controller = future.get() }, MoreExecutors.directExecutor())
        }
        awaitCondition("controller") { controller != null }
        var ready = false
        onMain { connection.doWhenReady { ready = true } }
        awaitCondition("ready") { ready }
        onMain {
            controller!!.pause()
            controller!!.volume = 0f
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

    private fun track(): Station {
        val id = UUID.randomUUID().toString()
        val samples = 16_000 * 30
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        val file = File(context.cacheDir, "heart-$id.wav")
            .also { it.writeBytes(bytes.array()); files += it }
        return Station(id, "Heart Check", file.toURI().toString(), StationSource.Local)
    }

    private fun Drawable.render(): Bitmap {
        val w = intrinsicWidth.takeIf { it > 0 } ?: 48
        val h = intrinsicHeight.takeIf { it > 0 } ?: 48
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            setBounds(0, 0, w, h)
            draw(Canvas(b))
        }
    }

    /** Share of pixels whose RGB is within rounding noise. */
    private fun similarity(a: Bitmap, b: Bitmap): Double {
        if (a.width != b.width || a.height != b.height) return 0.0
        var same = 0
        var total = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                total++
                val pa = a.getPixel(x, y)
                val pb = b.getPixel(x, y)
                val d = abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)) +
                    abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)) +
                    abs((pa and 0xFF) - (pb and 0xFF))
                if (d <= 36) same++
            }
        }
        return same.toDouble() / total
    }

    @Test fun favouriteActionDrawsHeart() = runBlocking {
        val t = track()
        onMain { connection.play(t, listOf(t)) }
        awaitCondition("playing") {
            controller?.currentMediaItem?.mediaId == t.id &&
                controller?.playbackState == Player.STATE_READY
        }
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var icon: android.graphics.drawable.Icon? = null
        withTimeout(15_000) {
            while (icon == null) {
                icon = mgr.activeNotifications
                    .firstOrNull { it.packageName == context.packageName }
                    ?.notification?.actions
                    ?.firstOrNull { a ->
                        (a.title?.toString() ?: "").contains("avour", ignoreCase = true)
                    }
                    ?.getIcon()
                if (icon == null) delay(200)
            }
        }
        val resId = runCatching { icon!!.resId }.getOrNull() ?: 0
        if (resId != 0) {
            assertEquals(R.drawable.ic_w_heart, resId)
        } else {
            val actual = icon!!.loadDrawable(context)!!.render()
            val expected = context.getDrawable(R.drawable.ic_w_heart)!!.render()
            assertTrue(
                "notification favourite icon is not the heart " +
                    "(similarity=${similarity(actual, expected)})",
                similarity(actual, expected) >= 0.95,
            )
        }
    }
}
