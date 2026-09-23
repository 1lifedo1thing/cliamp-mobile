package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicLedCoreTest {

    @Test
    fun bodyRisesFastAndFallsSlower() {
        val core = ClassicLedCore(4)
        val loud = FloatArray(64) { 1f }
        repeat(30) { core.push(loud, 1f / 30f) }
        for (v in core.body) assertTrue(v > 0.95f)

        val silence = FloatArray(64)
        core.push(silence, 1f / 30f)
        val afterOneFrame = core.body[0]
        assertTrue("fall too slow", afterOneFrame < 0.7f)
        assertTrue("fall too fast", afterOneFrame > 0.4f)
    }

    @Test
    fun peakHoldsThenFalls() {
        val core = ClassicLedCore(4)
        val loud = FloatArray(64) { 1f }
        repeat(60) { core.push(loud, 1f / 30f) }
        val peak = core.peak[0]
        assertTrue(peak > 0.9f)

        val silence = FloatArray(64)
        repeat(6) { core.push(silence, 1f / 30f) }
        assertEquals("peak moved during hold", peak, core.peak[0], 0.02f)

        repeat(30) { core.push(silence, 1f / 30f) }
        assertTrue(core.peak[0] < peak)
    }
}
