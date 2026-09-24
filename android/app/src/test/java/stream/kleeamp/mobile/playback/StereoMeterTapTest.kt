package stream.kleeamp.mobile.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoMeterTapTest {

    private val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    private fun pcm(vararg samples: Short): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { buffer.putShort(it) }
        buffer.flip()
        return buffer
    }

    @Test
    fun passesAudioThroughUnchanged() {
        val tap = StereoMeterTap()
        tap.configure(format)
        val input = pcm(1000, -1000, 2000, -2000)
        tap.queueInput(input)
        assertEquals(0, input.remaining())

        val output = tap.getOutput()
        assertEquals(8, output.remaining())
        assertEquals(1000, output.getShort(0).toInt())
        assertEquals(-1000, output.getShort(2).toInt())
    }

    /**
     * The speed-change path: ExoPlayer flushes the chain and keeps feeding.
     * Passthrough must survive the flush with output intact.
     */
    @Test
    fun flushKeepsPassthroughWorking() {
        val tap = StereoMeterTap()
        tap.configure(format)
        tap.queueInput(pcm(500, -500))
        tap.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertEquals(0, tap.getOutput().remaining())
        tap.queueInput(pcm(700, -700))
        val output = tap.getOutput()
        assertEquals(4, output.remaining())
        assertEquals(700, output.getShort(0).toInt())
        assertEquals(-700, output.getShort(2).toInt())
    }

    @Test
    fun silentInputKeepsTheMetersQuiet() {
        val tap = StereoMeterTap()
        tap.configure(format)
        tap.queueInput(pcm(0, 0, 0, 0))
        assertEquals(0f, PlaybackBus.stereo.value.leftLevel, 1e-6f)
        assertEquals(0f, PlaybackBus.stereo.value.rightLevel, 1e-6f)
    }
}
