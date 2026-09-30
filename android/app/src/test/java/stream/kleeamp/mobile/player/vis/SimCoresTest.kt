package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlameCoreTest {

    private val bands = FloatArray(24) { 0.9f }

    private fun hotCore(): FlameCore = FlameCore().also {
        it.ensure(10, 12)
        repeat(40) { _ -> it.push(bands) }
    }

    @Test
    fun loudFeedHeatsFromTheSourceRow() {
        val core = hotCore()
        assertTrue(core.heat.any { it > 0f })
        // Row 0 is the bottom source: hottest on average.
        val bottom = (0 until 12).sumOf { core.heatAt(it, 0).toDouble() } / 12
        val top = (0 until 12).sumOf { core.heatAt(it, 9).toDouble() } / 12
        assertTrue("bottom=$bottom top=$top", bottom > top)
    }

    @Test
    fun embersSurviveQuietInput() {
        val core = FlameCore().also { it.ensure(10, 12) }
        repeat(10) { core.push(FloatArray(0)) }
        // Source row holds the ember floor; nothing goes negative.
        for (x in 0 until 12) assertTrue(core.heatAt(x, 0) in 0.25f..0.55f)
        assertTrue(core.heat.all { it >= 0f })
    }

    @Test
    fun settleClears() {
        val core = hotCore()
        core.settle()
        assertTrue(core.heat.all { it == 0f })
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
