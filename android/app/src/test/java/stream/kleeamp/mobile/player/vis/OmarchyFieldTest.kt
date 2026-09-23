package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmarchyFieldTest {

    private val field = OmarchyField()

    @Test
    fun noiseIsDeterministicAndBounded() {
        val a = field.noiseAt(3.25, 9.5)
        val b = field.noiseAt(3.25, 9.5)
        assertEquals(a, b, 0.0)
        assertTrue(a in 0.0..1.0)
        assertTrue(field.noiseAt(-7.5, 130.25) in 0.0..1.0)
    }

    @Test
    fun jitterIsStablePerPixel() {
        assertEquals(field.jitter(2, 3), field.jitter(2, 3), 0.0)
        assertTrue(field.jitter(2, 3) in 0.0..1.0)
    }

    @Test
    fun wordmarkFitsAWidePanelAndLightsPixels() {
        val sampler = field.sampler(FloatArray(64) { 0.5f }, 30L, pxRows = 40, pxCols = 120)
        var lit = 0
        for (pr in 0 until 40) {
            for (pc in 0 until 120) {
                if (sampler.pixel(pr, pc).lit) lit++
            }
        }
        assertTrue("field drew nothing", lit > 50)
    }

    @Test
    fun tinyPanelDrawsFieldWithoutAMark() {
        val sampler = field.sampler(FloatArray(64) { 0.8f }, 30L, pxRows = 6, pxCols = 20)
        var lit = 0
        for (pr in 0 until 6) {
            for (pc in 0 until 20) {
                if (sampler.pixel(pr, pc).lit) lit++
            }
        }
        assertTrue(lit > 0)
    }
}
