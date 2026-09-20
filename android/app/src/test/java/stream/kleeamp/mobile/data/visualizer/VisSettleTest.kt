package stream.kleeamp.mobile.data.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pause must rest every family on the same floor BrickMeter settles to
 * (levels 0.04, peaks 0.06), and pushing live bands afterwards must resume
 * tracking - the idle dance is only for live playback with no FFT yet.
 */
class VisSettleTest {

    private val bands = FloatArray(64) { 0.8f }
    private val dt = 1f / 60f

    @Test
    fun classicPeakSettlesAndResumes() {
        val core = ClassicPeakCore(8)
        repeat(60) { core.push(bands, dt) }
        assertTrue(core.barPos[0] > 0.5f)

        core.settle()
        for (i in 0 until core.columns) {
            assertEquals(0.04f, core.barPos[i], 0.001f)
            assertEquals(0.06f, core.peakPos[i], 0.001f)
        }

        repeat(60) { core.push(bands, dt) }
        assertTrue(core.barPos[0] > 0.5f)
    }

    @Test
    fun classicLedSettlesAndResumes() {
        val core = ClassicLedCore(8)
        repeat(60) { core.push(bands, dt) }
        assertTrue(core.body[0] > 0.5f)

        core.settle()
        for (i in 0 until core.columns) {
            assertEquals(0.04f, core.body[i], 0.001f)
            assertEquals(0.06f, core.peak[i], 0.001f)
        }

        repeat(60) { core.push(bands, dt) }
        assertTrue(core.body[0] > 0.5f)
    }

    @Test
    fun stereoSettlesAndResumes() {
        val core = StereoCore()
        repeat(120) {
            core.push(StereoMetrics(0.9f, 0.9f, 0.95f, 0.95f), dt)
        }
        assertTrue(core.levels[0] > 0.5f)

        core.settle()
        assertEquals(0.04f, core.levels[0], 0.001f)
        assertEquals(0.04f, core.levels[1], 0.001f)
        assertEquals(0.06f, core.peaks[0], 0.001f)
        assertEquals(0.06f, core.peaks[1], 0.001f)

        repeat(120) {
            core.push(StereoMetrics(0.9f, 0.9f, 0.95f, 0.95f), dt)
        }
        assertTrue(core.levels[0] > 0.5f)
    }

    @Test
    fun kleeampSettlesAndResumes() {
        val core = KleeampCore(8)
        repeat(60) { core.push(bands, dt) }
        assertTrue(core.levels[0] > 0.3f)

        core.settle()
        for (i in 0 until core.columns) {
            assertEquals(0.04f, core.levels[i], 0.001f)
        }
        assertEquals(0f, core.bass, 0.001f)
        assertTrue(core.burst.sparks.isEmpty())

        repeat(60) { core.push(bands, dt) }
        assertTrue(core.levels[0] > 0.3f)
    }
}
