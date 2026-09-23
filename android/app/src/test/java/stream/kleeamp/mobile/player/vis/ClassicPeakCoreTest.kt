package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicPeakCoreTest {

    private val bands = FloatArray(64)

    @Test
    fun barsApproachTheBandLevel() {
        val core = ClassicPeakCore(8)
        bands.fill(1f)
        repeat(60) { core.push(bands, 1f / 60f) }
        for (bar in core.barPos) assertTrue("bar $bar did not rise", bar > 0.9f)
    }

    @Test
    fun peakLaunchesAboveTheFallingBar() {
        val core = ClassicPeakCore(8)
        bands.fill(1f)
        repeat(120) { core.push(bands, 1f / 60f) }
        val peakAfterRise = core.peakPos[0]

        bands.fill(0f)
        repeat(6) { core.push(bands, 1f / 60f) }
        assertTrue(core.peakPos[0] > core.barPos[0])
        assertTrue(core.peakPos[0] <= peakAfterRise + 0.05f)
        assertTrue(core.peakPos[0] <= 1f)
    }

    @Test
    fun peakFallsBackToTheBar() {
        val core = ClassicPeakCore(8)
        bands.fill(1f)
        repeat(120) { core.push(bands, 1f / 60f) }
        bands.fill(0f)
        repeat(600) { core.push(bands, 1f / 60f) }
        for (i in 0 until core.columns) {
            assertTrue(core.barPos[i] < 0.01f)
            assertEquals(core.barPos[i], core.peakPos[i], 0.01f)
        }
    }

    @Test
    fun silentInputKeepsStateBounded() {
        val core = ClassicPeakCore(8)
        repeat(30) { core.push(bands, 1f / 60f) }
        for (i in 0 until core.columns) {
            assertTrue(core.barPos[i] in 0f..1f)
            assertTrue(core.peakPos[i] in 0f..1f)
        }
    }
}
