package stream.kleeamp.mobile.data.visualizer

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
}
