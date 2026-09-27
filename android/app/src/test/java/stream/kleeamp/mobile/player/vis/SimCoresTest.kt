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

    @Test
    fun pourAccumulatesAndSettles() {
        val core = SandCore(16, 12)
        repeat(60) { core.push(FloatArray(24) { 0.9f }) }
        assertTrue(core.grid.any { it > 0 })
        core.settle()
        assertTrue(core.grid.all { it == 0.toByte() })
        assertTrue(core.grains().isEmpty())
    }

    @Test
    fun bassTransientFiresTheShower() {
        val core = SandCore(16, 12)
        repeat(5) { core.push(FloatArray(24)) }
        // Sudden full-scale onset after quiet: shower must fire.
        core.push(FloatArray(24) { 1f })
        assertTrue(core.showerActive || core.grains().isNotEmpty())
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
