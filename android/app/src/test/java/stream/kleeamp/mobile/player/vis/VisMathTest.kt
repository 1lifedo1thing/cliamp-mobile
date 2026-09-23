package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisMathTest {

    @Test
    fun scatterHashStaysInRangeAndRepeats() {
        for (frame in 0L..50L) {
            val h = VisMath.scatterHash(3, 7, 11, frame)
            assertTrue("hash $h out of range", h >= 0f && h < 1f)
        }
        assertEquals(
            VisMath.scatterHash(3, 7, 11, 40L),
            VisMath.scatterHash(3, 7, 11, 40L),
            0f,
        )
    }

    @Test
    fun tierThresholdsMatchCliamp() {
        assertEquals(0, VisMath.tier(0.0f))
        assertEquals(0, VisMath.tier(0.29f))
        assertEquals(1, VisMath.tier(0.3f))
        assertEquals(1, VisMath.tier(0.59f))
        assertEquals(2, VisMath.tier(0.6f))
        assertEquals(2, VisMath.tier(1.0f))
    }

    @Test
    fun resampleAverageShrinksByFractionalOverlap() {
        val bands = floatArrayOf(1f, 0f, 0f, 0f)
        val out = VisMath.resampleAverage(bands, 2)
        assertEquals(2, out.size)
        assertEquals(0.5f, out[0], 1e-4f)
        assertEquals(0f, out[1], 1e-4f)
    }

    @Test
    fun resampleLinearExpandsAndKeepsEnds() {
        val bands = floatArrayOf(0f, 1f)
        val out = VisMath.resampleLinear(bands, 3)
        assertEquals(3, out.size)
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(0.5f, out[1], 1e-4f)
        assertEquals(1f, out[2], 1e-4f)
    }

    @Test
    fun idleBandsAreBoundedAndStable() {
        val first = VisMath.idleBands(24, 1.25)
        val second = VisMath.idleBands(24, 1.25)
        assertEquals(24, first.size)
        assertTrue(first.contentEquals(second))
        for (v in first) assertTrue(v in 0f..0.96f)
    }

    @Test
    fun easeBandsAttacksFastAndDecaysSlow() {
        val prev = floatArrayOf(0f, 1f)
        val out = VisMath.easeBands(prev, floatArrayOf(1f, 0f))
        // cliamp Analyze blend: 0.6 new on rises, 0.25 new on falls.
        assertEquals(0.6f, out[0], 1e-4f)
        assertEquals(0.75f, out[1], 1e-4f)
    }

    @Test
    fun easeBandsSnapsOnResize() {
        val out = VisMath.easeBands(FloatArray(4), FloatArray(8) { 1f })
        assertEquals(8, out.size)
        for (v in out) assertEquals(1f, v, 1e-4f)
    }
}
