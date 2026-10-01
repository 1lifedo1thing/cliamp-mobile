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
    fun petalCountScalesWithEnergy() {
        assertEquals(12, SakuraCore.petalCount(0f))
        assertEquals(28, SakuraCore.petalCount(1f))
    }

    @Test
    fun petalsDriftDownOverTime() {
        // Same petal stamps lower (larger y) a few ticks later; 8 frames at
        // speed 1..2 advance the window 1..2 dots, far from any wrap.
        val dots = { f: Long -> SakuraCore.dots(3, f, 200, 120) }
        val early = dots(0L).minOf { it.second }
        val late = dots(8L).minOf { it.second }
        assertTrue("early=$early late=$late", late > early)
    }

    @Test
    fun petalsSpreadAcrossThePanel() {
        val xs = (0 until 12).flatMap { SakuraCore.dots(it, 100L, 106, 120) }.map { it.first }
        assertTrue("petals bunched: $xs", xs.max() - xs.min() > 60)
    }

    @Test
    fun shapesMatchCliampTable() {
        assertEquals(9, SakuraCore.shapes.size)
        assertEquals(6, SakuraCore.shapes.take(3).maxOf { it.size })
        assertEquals(4, SakuraCore.shapes[3].size)
        assertTrue(SakuraCore.shapes.all { it.size in 2..6 })
    }
}

class FireworkCoreTest {

    @Test
    fun burstCountScalesWithEnergy() {
        assertEquals(5, FireworkCore.burstCount(0f))
        assertEquals(14, FireworkCore.burstCount(1f))
    }

    @Test
    fun risePhaseShowsAFourDotTrail() {
        // Burst 1 at frame 0: offset shifts it, but some early frame in the
        // first cycle must show the short launch trail.
        val trail = (0L until FireworkCore.CYCLE).firstOrNull { f ->
            FireworkCore.dots(1, f, 200, 120, FloatArray(10) { 0.8f }).size == 4
        }
        assertTrue("no 4-dot trail in a full cycle", trail != null)
    }

    @Test
    fun loudBurstsThrowMoreParticles() {
        // One full cycle per feed: loud throws ~2x the particles per burst
        // frame (36 vs 18) while trail frames tie, so the totals separate.
        fun total(bands: FloatArray): Int =
            (0L until FireworkCore.CYCLE).sumOf { f ->
                FireworkCore.dots(2, f, 200, 120, bands).size
            }
        val quiet = total(FloatArray(10))
        val loud = total(FloatArray(10) { 1f })
        assertTrue("quiet=$quiet loud=$loud", loud > quiet + 100)
    }

    @Test
    fun burstCentersMoveBetweenCycles() {
        // Consecutive cycles share the 104729 stride, which is 1 mod 106:
        // without prime spreading every cycle would fire from the column
        // next door. Average dot x must jump, not crawl.
        fun meanX(cycle: Long): Double {
            val dots = FireworkCore.dots(2, cycle * FireworkCore.CYCLE, 106, 120, FloatArray(10) { 0.8f })
            return dots.map { it.first }.average()
        }
        val jumps = (0L until 6L).map { kotlin.math.abs(meanX(it + 1) - meanX(it)) }
        assertTrue("centers crawl: $jumps", jumps.average() > 8.0)
    }

    @Test
    fun dotsAreDeterministic() {
        val bands = FloatArray(10) { 0.6f }
        assertEquals(
            FireworkCore.dots(2, 40L, 200, 120, bands),
            FireworkCore.dots(2, 40L, 200, 120, bands),
        )
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
    fun energyRaisesOnes() {
        var quiet = 0
        var loud = 0
        for (i in 0 until 200) {
            if (BinaryCore.bit(i % 8, i % 14, i % 10, i / 3, 0f)) quiet++
            if (BinaryCore.bit(i % 8, i % 14, i % 10, i / 3, 1f)) loud++
        }
        // oneProb is 0.15 quiet vs 0.75 loud.
        assertTrue("loud=$loud quiet=$quiet", loud > quiet + 60)
    }

    @Test
    fun hotOnesGlowBright() {
        assertEquals(2, BinaryCore.tier(true, 0.9f))
        assertEquals(1, BinaryCore.tier(true, 0.2f))
        assertEquals(1, BinaryCore.tier(false, 0.5f))
        assertEquals(0, BinaryCore.tier(false, 0.1f))
    }

    @Test
    fun hotterBandsScrollFaster() {
        assertTrue(BinaryCore.speed(1f) < BinaryCore.speed(0f))
        assertEquals(1, BinaryCore.speed(1f))
        assertEquals(4, BinaryCore.speed(0f))
    }
}
