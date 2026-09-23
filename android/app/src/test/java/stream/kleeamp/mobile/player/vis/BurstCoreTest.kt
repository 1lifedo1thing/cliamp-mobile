package stream.kleeamp.mobile.player.vis

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstCoreTest {

    private val step = 1f / 20f

    @Test
    fun aLoudOnsetFiresTheBurst() {
        val core = BurstCore(Random(1))
        core.push(FloatArray(64) { 0.9f }, step)
        assertTrue(core.sparks.isNotEmpty())
        assertTrue(core.shockLife > 0f)
        assertTrue(core.flash > 0f)
    }

    @Test
    fun cooldownStopsASecondBurstAtOnce() {
        val core = BurstCore(Random(1))
        val bands = FloatArray(64) { 0.9f }
        core.push(bands, step)
        val afterFirst = core.sparks.size
        core.push(bands, step)
        assertEquals(afterFirst, core.sparks.size)
    }

    @Test
    fun silenceExpiresSparksAndTheShock() {
        val core = BurstCore(Random(1))
        core.push(FloatArray(64) { 0.9f }, step)
        repeat(100) { core.push(FloatArray(64), step) }
        assertTrue(core.sparks.isEmpty())
        assertEquals(0f, core.shockLife, 1e-6f)
        assertEquals(0f, core.flash, 1e-6f)
    }

    @Test
    fun sparksFallWithGravity() {
        val core = BurstCore(Random(7))
        core.push(FloatArray(64) { 0.9f }, step)
        val spark = core.sparks.first()
        val vyBefore = spark.vy
        core.push(FloatArray(64), step)
        assertTrue(core.sparks.isEmpty() || spark.vy > vyBefore)
    }
}
