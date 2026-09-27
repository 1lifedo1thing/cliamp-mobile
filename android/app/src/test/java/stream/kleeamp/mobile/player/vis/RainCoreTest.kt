package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RainCoreTest {

    @Test
    fun dropParametersStayInRange() {
        repeat(32) { col ->
            val drop = RainCore.drop(col, 24)
            assertTrue(drop.speed in 1..3)
            assertTrue(drop.len in 2..4)
            assertTrue(drop.offset in 0 until drop.cycleLen)
            assertEquals(24 + drop.len + 3, drop.cycleLen)
        }
    }

    @Test
    fun headCyclesThroughItsLane() {
        val drop = RainCore.drop(3, 24)
        val seen = (0 until drop.cycleLen * drop.speed + 1).map {
            RainCore.headPos(it.toLong(), drop)
        }.toSet()
        assertTrue(seen.all { it in 0 until drop.cycleLen })
        assertEquals(0, RainCore.headPos((-drop.offset * drop.speed).toLong(), drop))
    }

    @Test
    fun energyOpensMoreGatesThanSilence() {
        val quiet = (0 until 16).count { RainCore.active(it, it, 0L, 0f) }
        val loud = (0 until 16).count { RainCore.active(it, it, 0L, 1f) }
        assertTrue(loud > quiet)
    }
}
