package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoCoreTest {

    @Test
    fun wordHasSevenLetterWidth() {
        assertEquals(7 * 5 + 6 * 2, LogoCore.TOTAL_W)
        assertTrue(LogoCore.pixelOn(0, 0))
        // Gap columns stay dark.
        assertEquals(false, LogoCore.pixelOn(5, 0))
    }

    @Test
    fun silenceDissolvesMostDots() {
        var lit = 0
        var total = 0
        for (x in 0 until LogoCore.TOTAL_W) {
            for (y in 0 until LogoCore.LETTER_H) {
                if (!LogoCore.pixelOn(x, y)) continue
                total++
                if (LogoCore.dotOn(x, y, 0f, 100L)) lit++
            }
        }
        assertTrue(total > 0)
        assertTrue(lit < total)
    }

    @Test
    fun fullEnergyFillsTheWord() {
        for (x in 0 until LogoCore.TOTAL_W) {
            for (y in 0 until LogoCore.LETTER_H) {
                if (LogoCore.pixelOn(x, y)) {
                    assertTrue(LogoCore.dotOn(x, y, 1f, 100L))
                }
            }
        }
    }
}

class TerrainCoreTest {

    @Test
    fun historyScrollsAndSettles() {
        val core = TerrainCore(8)
        core.push(0.5f)
        core.push(1f)
        val heights = core.heights()
        assertEquals(8, heights.size)
        assertEquals(0f, heights[0], 1e-6f)
        assertEquals(1f, heights[7], 1e-6f)
        core.settle()
        assertTrue(core.heights().all { it == 0f })
    }
}

class ScopeCoreTest {

    @Test
    fun delayStaysInsideTheLine() {
        val samples = FloatArray(256) { it / 256f }
        val delay = ScopeCore.delayFor(100L, samples.size)
        assertTrue(delay in 1 until samples.size)
        val (x, y) = ScopeCore.point(samples, delay, 0, 64)
        assertTrue(x in 0f..1f && y in 0f..1f)
    }

    @Test
    fun degenerateInputGivesOrigin() {
        assertEquals(0f to 0f, ScopeCore.point(FloatArray(0), 0, 0, 8))
    }
}

class HeartbeatCoreTest {

    @Test
    fun shapingSharpensPeaks() {
        assertEquals(0.25f, HeartbeatCore.shaped(0.5f), 1e-6f)
        assertEquals(-0.25f, HeartbeatCore.shaped(-0.5f), 1e-6f)
        assertEquals(0.5f, HeartbeatCore.yFrac(0f), 1e-6f)
    }
}

class AsciiCoreTest {

    @Test
    fun shadeStepsDown() {
        assertEquals(4, AsciiCore.shade(1f, 0.5f, 0.75f))
        assertEquals(3, AsciiCore.shade(0.7f, 0.5f, 0.75f))
        assertEquals(2, AsciiCore.shade(0.65f, 0.5f, 0.75f))
        assertEquals(1, AsciiCore.shade(0.57f, 0.5f, 0.75f))
        assertEquals(0, AsciiCore.shade(0.51f, 0.5f, 0.75f))
        assertEquals(0, AsciiCore.shade(0.4f, 0.5f, 0.75f))
    }
}

class MosaicCoreTest {

    @Test
    fun loudIgnitesAndQuietDecays() {
        val core = MosaicCore(4, 6, seed = 3L)
        repeat(4) { core.push(FloatArray(8) { 1f }) }
        assertTrue(core.value.any { it > 0f })
        repeat(30) { core.push(FloatArray(8)) }
        assertTrue(core.value.all { it == 0f })
        core.settle()
        assertTrue(core.value.all { it == 0f })
    }
}

class RedSectorCoreTest {

    @Test
    fun heightsAveragePairs() {
        val heights = RedSectorCore.barHeights(floatArrayOf(0f, 1f, 0.5f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f))
        assertEquals(5, heights.size)
        assertEquals(0.5f, heights[0], 1e-6f)
        assertEquals(0.5f, heights[1], 1e-6f)
    }

    @Test
    fun rotationTurnsAndProjectsInside() {
        val a = RedSectorCore.angle(100L)
        assertTrue(a > 0.0)
        val (rx, _) = RedSectorCore.rotY(1.0, 0.0, RedSectorCore.angle(0L))
        assertEquals(1.0, rx, 1e-9)
        val (px, py) = RedSectorCore.project(0.0, 0.0, 300f, 200f)
        assertEquals(150f, px, 1e-6f)
        assertEquals(100f, py, 1e-6f)
    }

    @Test
    fun starsDriftInStageSpace() {
        val (x0, _, _) = RedSectorCore.star(0, 0L)
        val (x1, _, _) = RedSectorCore.star(0, 500L)
        assertTrue(x0 in -1.0..1.0 && x1 in -1.0..1.0)
        assertTrue(x0 != x1)
    }
}
