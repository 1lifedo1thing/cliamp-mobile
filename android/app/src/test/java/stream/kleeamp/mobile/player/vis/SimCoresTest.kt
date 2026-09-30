package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlameCoreTest {

    private val bands = FloatArray(24) { 0.9f }

    @Test
    fun loudFeedHeatsTheTop() {
        val core = FlameCore(12, 10)
        assertEquals(-1, core.tierAt(5, 0))
        repeat(40) { core.push(bands) }
        assertTrue((0 until 12).any { x -> (0 until 10).any { y -> core.tierAt(x, y) >= 0 } })
    }

    @Test
    fun silenceCoolsToEmpty() {
        val core = FlameCore(12, 10)
        repeat(40) { core.push(bands) }
        repeat(200) { core.push(FloatArray(24)) }
        assertTrue((0 until 12).all { x -> (0 until 10).all { y -> core.tierAt(x, y) < 0 } })
        core.settle()
        assertTrue((0 until 12).all { x -> (0 until 10).all { y -> core.tierAt(x, y) < 0 } })
    }
}

class SandCoreTest {

    private fun loudCore(): SandCore = SandCore().also { it.ensure(24, 32) }

    @Test
    fun pourAccumulatesAndSettles() {
        val core = loudCore()
        repeat(60) { core.push(FloatArray(24) { 0.9f }, it.toLong()) }
        assertTrue(core.grid.any { it > 0 })
        core.settle()
        assertTrue(core.grid.all { it == 0.toByte() })
        assertTrue(!core.explosionActive)
    }

    @Test
    fun silenceStaysEmpty() {
        val core = loudCore()
        repeat(30) { core.push(FloatArray(24), it.toLong()) }
        assertTrue(core.grid.all { it == 0.toByte() })
    }

    @Test
    fun bassTransientDetonatesAFullBed() {
        val core = loudCore()
        // Constant loud feed fills the bed without transients (delta ~ 0,
        // so no explosion while filling).
        repeat(120) { core.push(FloatArray(24) { 0.9f }, it.toLong()) }
        // Dip to quiet, then slam back: the rising edge must detonate.
        repeat(3) { core.push(FloatArray(24), (120 + it).toLong()) }
        core.push(FloatArray(24) { 1f }, 123L)
        assertTrue("bed never detonated", core.explosionActive)
        // The burst clears: particles fly off and the bed restarts empty.
        repeat(200) { core.push(FloatArray(24), (124 + it).toLong()) }
        assertTrue(!core.explosionActive)
    }

    @Test
    fun tiersFollowFrequencyThirds() {
        val core = SandCore().also { it.ensure(8, 60) }
        // Only the lowest third hot (10-band input, no resample bleed):
        // spawned grains must be red tier 3.
        val bands = FloatArray(10).also { for (i in 0 until 3) it[i] = 0.9f }
        repeat(40) { core.push(bands, it.toLong()) }
        val tiers = core.grid.filter { it > 0 }
        assertTrue(tiers.isNotEmpty())
        assertTrue("low bands must pour red: $tiers", tiers.all { it == 3.toByte() })
    }

    @Test
    fun resizeClearsLikeATerminal() {
        val core = loudCore()
        repeat(60) { core.push(FloatArray(24) { 0.9f }, it.toLong()) }
        assertTrue(core.grid.any { it > 0 })
        core.ensure(10, 10)
        assertTrue(core.grid.all { it == 0.toByte() })
    }
}

class GeyserCoreTest {

    @Test
    fun loudFeedSpraysAndSettles() {
        val core = GeyserCore(seed = 7L)
        repeat(60) { core.push(FloatArray(24) { 0.9f }) }
        val drops = core.drops()
        assertTrue(drops.isNotEmpty())
        // Ballistics may carry drops above the frame; they never sink past it.
        assertTrue(drops.all { it.y <= 180f })
        assertTrue(drops.all { it.tier in 1..3 })
        core.settle()
        assertTrue(core.drops().isEmpty())
    }

    @Test
    fun silenceStaysDry() {
        val core = GeyserCore(seed = 7L)
        repeat(10) { core.push(FloatArray(24)) }
        assertEquals(0, core.drops().size)
    }
}
