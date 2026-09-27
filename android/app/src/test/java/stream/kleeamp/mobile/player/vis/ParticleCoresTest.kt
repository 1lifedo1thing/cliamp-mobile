package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScatterCoreTest {

    @Test
    fun energyLightsMoreDotsThanSilence() {
        var quiet = 0
        var loud = 0
        for (col in 0 until 8) {
            for (row in 0 until 8) {
                if (ScatterCore.lit(1, col, row, 8, 0L, 0f)) quiet++
                if (ScatterCore.lit(1, col, row, 8, 0L, 1f)) loud++
            }
        }
        assertTrue(loud > quiet)
    }

    @Test
    fun degenerateFieldStaysDark() {
        assertEquals(false, ScatterCore.lit(0, 0, 0, 1, 0L, 1f))
    }
}

class SakuraCoreTest {

    @Test
    fun petalsStayOnScreen() {
        repeat(SakuraCore.PETALS) { i ->
            val petal = SakuraCore.petal(i, 100L, 300f, 200f, 1f)
            assertTrue(petal.x in -60f..360f)
            assertTrue(petal.y in -30f..230f)
            assertTrue(petal.r > 0f)
        }
    }

    @Test
    fun silenceParksMostPetals() {
        val flying = (0 until SakuraCore.PETALS).count {
            SakuraCore.petal(it, 100L, 300f, 200f, 0f).r > 0f
        }
        assertEquals(6, flying)
    }
}

class FireworkCoreTest {

    @Test
    fun burstCountScalesWithEnergy() {
        assertEquals(5, FireworkCore.burstCount(0f))
        assertEquals(14, FireworkCore.burstCount(1f))
    }

    @Test
    fun sparksStayInFrameAndFade() {
        val sparks = FireworkCore.burst(0, 40L, 300f, 200f, 1f)
        assertEquals(FireworkCore.SPOKES, sparks.size)
        assertTrue(sparks.all { it.x in 0f..300f && it.y in 0f..200f })
        assertTrue(sparks.all { it.alpha in 0f..1f })
    }

    @Test
    fun risePhaseShowsTheTrail() {
        val sparks = FireworkCore.burst(1, 0L, 300f, 200f, 1f)
        assertTrue(sparks.size in 1..3)
    }
}

class BubblesCoreTest {

    @Test
    fun bubblesRiseAndStaySized() {
        repeat(BubblesCore.COUNT) { i ->
            val b = BubblesCore.bubble(i, 200L, 300f, 200f, 0.5f)
            assertTrue(b.r in 4f..10f)
            assertTrue(b.alpha in 0f..0.9f)
        }
    }
}

class FireflyCoreTest {

    @Test
    fun fliesStayInTheMeadow() {
        repeat(FireflyCore.FLIES) { i ->
            FireflyCore.fly(i, 500L, 300f, 200f, 0.5f, 0.5f)?.let { fly ->
                assertTrue(fly.x in 0f..300f)
                assertTrue(fly.y in 0f..200f)
            }
        }
    }

    @Test
    fun grassIsLowAndRagged() {
        val h0 = FireflyCore.grassH(0f, 300f)
        val h1 = FireflyCore.grassH(150f, 300f)
        assertTrue(h0 in 1f..12f && h1 in 1f..12f)
        assertTrue(h0 != h1)
    }
}

class BinaryCoreTest {

    @Test
    fun bitsAreBinary() {
        repeat(64) { i ->
            val bit = BinaryCore.bit(i % 8, i % 14, 14, i.toLong(), 0.7f)
            assertTrue(bit == '0' || bit == '1')
        }
    }

    @Test
    fun energyRaisesOnes() {
        var quiet = 0
        var loud = 0
        for (i in 0 until 200) {
            if (BinaryCore.bit(i % 8, i % 14, 14, i.toLong(), 0f) == '1') quiet++
            if (BinaryCore.bit(i % 8, i % 14, 14, i.toLong(), 1f) == '1') loud++
        }
        assertTrue(loud > quiet)
    }
}
