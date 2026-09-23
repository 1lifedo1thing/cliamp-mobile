package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import stream.kleeamp.mobile.playback.PlaybackBus

class WaveCoreTest {

    @Test
    fun fromBytesMapsSilenceToZero() {
        val out = WaveCore.fromBytes(ByteArray(4) { 128.toByte() })
        for (v in out) assertEquals(0f, v, 0.01f)
    }

    @Test
    fun fromBytesMapsRails() {
        val out = WaveCore.fromBytes(byteArrayOf(0.toByte(), 255.toByte()))
        assertEquals(-1f, out[0], 0.01f)
        assertEquals(1f, out[1], 0.01f)
    }

    @Test
    fun traceMapsFullScaleToEdges() {
        val out = WaveCore.trace(floatArrayOf(1f, -1f), 2)
        assertEquals(0f, out[0], 0.001f)
        assertEquals(1f, out[1], 0.001f)
    }

    @Test
    fun traceCentersSilence() {
        val out = WaveCore.trace(FloatArray(64), 32)
        for (v in out) assertEquals(0.5f, v, 0.001f)
    }

    @Test
    fun traceKeepsImpulsePosition() {
        val samples = FloatArray(100)
        samples[75] = 1f
        val out = WaveCore.trace(samples, 100)
        // Nearest downsampling lands the impulse at its own column.
        assertTrue("impulse column ${out[75]} not near top", out[75] < 0.05f)
        assertEquals(0.5f, out[10], 0.001f)
    }

    @Test
    fun traceStaysInBounds() {
        val samples = FloatArray(64) { if (it % 2 == 0) 4f else -4f }
        val out = WaveCore.trace(samples, 64)
        for (v in out) assertTrue("y $v out of bounds", v in 0f..1f)
    }

    @Test
    fun frameFollowsWaveformBusAndSettles() {
        val frame = WaveFrame(24)
        val live = FloatArray(64) { 0.5f }
        PlaybackBus.publishWaveform(live)
        try {
            frame.tick(null, StereoMetrics.silent, 1f / 60f, 0.0)
            assertTrue(frame.samples.contentEquals(live))

            frame.settle()
            assertTrue(frame.samples.isEmpty())
        } finally {
            PlaybackBus.publishWaveform(FloatArray(0))
        }
    }
}
