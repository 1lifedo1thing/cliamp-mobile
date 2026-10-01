package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoCoreTest {

    @Test
    fun wordIsKleeampSevenLettersWide() {
        assertEquals(7 * 5 + 6 * 2, LogoCore.TOTAL_W)
        // K's middle arm pixel.
        assertTrue(LogoCore.pixelSet(0, 0, 3))
        // Gap columns stay dark.
        assertEquals(false, LogoCore.pixelSet(0, 5, 0))
    }

    @Test
    fun lettersWireAcrossTheSpectrum() {
        assertEquals(listOf(0, 2, 3, 5, 6, 8, 9), LogoCore.letterBand.toList())
    }

    @Test
    fun silenceDissolvesMostDots() {
        var lit = 0
        var total = 0
        val fill = LogoCore.fill(0f)
        for (li in 0 until LogoCore.LETTERS) {
            for (px in 0 until LogoCore.LETTER_W) {
                for (py in 0 until LogoCore.LETTER_H) {
                    if (!LogoCore.pixelSet(li, px, py)) continue
                    total++
                    if (LogoCore.dotKept(li, px, py, 100L, fill)) lit++
                }
            }
        }
        assertTrue(total > 0)
        assertTrue("silence must dissolve, lit=$lit of $total", lit < total)
    }

    @Test
    fun fullEnergyFillsTheWord() {
        var lit = 0
        var total = 0
        val fill = LogoCore.fill(1f)
        for (li in 0 until LogoCore.LETTERS) {
            for (px in 0 until LogoCore.LETTER_W) {
                for (py in 0 until LogoCore.LETTER_H) {
                    if (!LogoCore.pixelSet(li, px, py)) continue
                    total++
                    if (LogoCore.dotKept(li, px, py, 100L, fill)) lit++
                }
            }
        }
        assertTrue("lit=$lit of $total", lit.toDouble() / total > 0.85)
    }

    @Test
    fun wordJustifiesAcrossNarrowPanels() {
        // 80-dot panel: scale 1, leftover spread so the last letter ends
        // at or past the right edge region instead of mid-panel.
        assertEquals(1, LogoCore.scaleX(80))
        assertEquals(12, LogoCore.columnStep(80))
        val end = 6 * LogoCore.columnStep(80) + LogoCore.LETTER_W
        assertTrue("end=$end", end in 70..80)
    }

    @Test
    fun bounceRisesWithEnergy() {
        val still = LogoCore.bounce(0f, 20, 0, 0L)
        val loud = LogoCore.bounce(1f, 20, 0, 0L)
        assertTrue("still=$still loud=$loud", loud > still)
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

    private fun wiredCore(): MosaicCore = MosaicCore().also { it.ensureGrid(6, 8, 10) }

    @Test
    fun wiringIsRowBiasedAndDeterministic() {
        val a = wiredCore()
        val b = wiredCore()
        assertTrue(a.bandOf.contentEquals(b.bandOf))
        assertTrue(a.threshold.contentEquals(b.threshold))
        // Top row listens high, bottom row low.
        val topAvg = (0 until 8).sumOf { a.bandOf[it].toDouble() } / 8
        val botAvg = (0 until 8).sumOf { a.bandOf[5 * 8 + it].toDouble() } / 8
        assertTrue("top=$topAvg bottom=$botAvg", topAvg > botAvg)
        assertTrue(a.threshold.all { it in 0.04f..0.78f })
    }

    @Test
    fun loudIgnitesAndQuietDecays() {
        val core = MosaicCore().also { it.ensureGrid(4, 6, 10) }
        repeat(4) { core.push(FloatArray(10) { 1f }) }
        assertTrue(core.value.any { it > 0f })
        repeat(200) { core.push(FloatArray(10)) }
        assertTrue(core.value.all { it == 0f })
        core.settle()
        assertTrue(core.value.all { it == 0f })
    }

    @Test
    fun levelThresholdsMatchCliamp() {
        val core = wiredCore()
        assertEquals(0, core.levelOf(0.04f))
        assertEquals(1, core.levelOf(0.05f))
        assertEquals(2, core.levelOf(0.15f))
        assertEquals(3, core.levelOf(0.28f))
        assertEquals(4, core.levelOf(0.45f))
        assertEquals(5, core.levelOf(0.65f))
        assertEquals(6, core.levelOf(0.85f))
        assertEquals(6, core.levelOf(1.05f))
    }

    @Test
    fun tileCountFitsTilesWithGaps() {
        val core = wiredCore()
        // 100-unit panel holds cliamp's 33 tiles.
        assertEquals(33, core.tileCount(100f, 1f))
        assertEquals(0, core.tileCount(1f, 1f))
    }
}

class RedSectorCoreTest {

    private fun drivenCore(): RedSectorCore = RedSectorCore().also {
        it.ensure(48, 96)
        repeat(60) { _ -> it.advance(FloatArray(10) { 0.8f }) }
    }

    @Test
    fun heightsTrackPairMaxima() {
        val core = RedSectorCore().also { it.ensure(48, 96) }
        // Settle the envelopes on flat mid input, then slam bar 0's pair:
        // bar 0 takes the louder of bands 0..1 and must shoot past the rest.
        repeat(60) { core.advance(FloatArray(10) { 0.5f }) }
        val bands = FloatArray(10) { 0.1f }.also { it[1] = 0.9f }
        repeat(5) { core.advance(bands) }
        val heights = core.heights()
        assertTrue("bar0=${heights[0]} bar2=${heights[2]}", heights[0] > heights[2] + 0.2)
        assertTrue(heights.all { it in 0.40..2.30 })
    }

    @Test
    fun barTagsFollowHeightTiers() {
        val core = RedSectorCore()
        assertEquals(RedSectorCore.TAG_LOW.toByte(), core.barTag(0.40))
        assertEquals(RedSectorCore.TAG_MID.toByte(), core.barTag(0.40 + 0.3 * 1.90))
        assertEquals(RedSectorCore.TAG_HIGH.toByte(), core.barTag(2.30))
    }

    @Test
    fun starHashIsStableAndBounded() {
        val h0 = RedSectorCore().starHash(7, 2)
        assertEquals(h0, RedSectorCore().starHash(7, 2), 0.0)
        assertTrue(h0 in 0.0..1.0)
        val spread = (1..40).map { RedSectorCore().starHash(it, 0) }
        assertTrue(spread.max() - spread.min() > 0.8)
    }

    @Test
    fun sceneDrawsBarsOverStars() {
        val core = drivenCore()
        core.draw(100L)
        val tags = core.cells.filter { it > 0 }
        assertTrue(tags.isNotEmpty())
        assertTrue("no bar tags drawn", tags.any { it >= RedSectorCore.TAG_LOW.toByte() })
    }

    @Test
    fun degenerateProjectionCannotHang() {
        val core = drivenCore()
        // NaN/Infinity edges are skipped, not looped on.
        core.drawLine(Double.NaN, 0.0, 1.0, 1.0, RedSectorCore.TAG_HIGH.toByte())
        core.drawLine(0.0, 0.0, Double.POSITIVE_INFINITY, 1.0, RedSectorCore.TAG_HIGH.toByte())
    }
}
