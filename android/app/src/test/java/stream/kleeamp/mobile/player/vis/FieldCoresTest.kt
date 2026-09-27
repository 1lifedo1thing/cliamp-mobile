package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseCoreTest {

    @Test
    fun silenceCollapsesToTheFloor() {
        val r = PulseCore.radius(0.0, FloatArray(8), 0f, 0L)
        assertTrue(r < 0.15)
    }

    @Test
    fun fullEnergyPushesPastOne() {
        val r = PulseCore.radius(0.0, FloatArray(8) { 1f }, 1f, 10L)
        assertTrue(r > 1.0)
    }

    @Test
    fun shockFiresLoudAndRestsQuiet() {
        val loud = PulseCore.shock(0L, 1f)
        assertTrue(loud != null && loud.second > 0.05)
        assertNull(PulseCore.shock(0L, 0f))
    }

    @Test
    fun rotationAdvancesWithFrames() {
        val a = PulseCore.rotation(0L, 0.5f)
        val b = PulseCore.rotation(100L, 0.5f)
        assertTrue(b > a)
    }
}

class MirrorCoreTest {

    @Test
    fun centerBarsReachFurtherThanEdges() {
        val bars = 9
        val center = MirrorCore.radiusFrac(4, bars, 1f, 0.0)
        val edge = MirrorCore.radiusFrac(0, bars, 1f, 0.0)
        assertTrue(center > edge)
        assertTrue(center <= 0.81f)
    }

    @Test
    fun silenceShrinksToABreath() {
        assertTrue(MirrorCore.radiusFrac(4, 9, 0f, 0.0) < 0.3f)
    }
}

class RetroCoreTest {

    @Test
    fun waveFollowsTheBands() {
        val pts = RetroCore.wavePoints(floatArrayOf(0f, 1f), 5)
        assertEquals(5, pts.size)
        assertEquals(0f, pts.first(), 1e-6f)
        assertEquals(1f, pts.last(), 1e-6f)
    }

    @Test
    fun scrollPhaseWraps() {
        val a = RetroCore.scrollPhase(0L)
        val b = RetroCore.scrollPhase(13L)
        assertTrue(a in 0.0..1.0 && b in 0.0..1.0)
        assertTrue(b > a)
    }
}
