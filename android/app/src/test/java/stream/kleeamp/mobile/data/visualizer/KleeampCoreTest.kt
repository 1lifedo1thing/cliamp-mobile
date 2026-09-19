package stream.kleeamp.mobile.data.visualizer

import org.junit.Assert.assertTrue
import org.junit.Test

class KleeampCoreTest {

    @Test
    fun barsFollowTheSpectrum() {
        val core = KleeampCore(24)
        val bands = FloatArray(64) { 1f }
        repeat(40) { core.push(bands, 1f / 60f) }
        for (level in core.barLevels) assertTrue("bar $level did not rise", level > 0.9f)
        for (level in core.levels) assertTrue(level > 0.9f)
    }

    @Test
    fun bassSnapsUpAndReleasesSlowly() {
        val core = KleeampCore(24)
        core.push(FloatArray(64) { 0.9f }, 1f / 60f)
        assertTrue(core.bass > 0.85f)
        core.push(FloatArray(64), 1f / 60f)
        assertTrue("bass dropped too fast: ${core.bass}", core.bass > 0.5f)
    }

    @Test
    fun aLoudOnsetReachesTheBurst() {
        val core = KleeampCore(24)
        core.push(FloatArray(64) { 0.9f }, 1f / 60f)
        assertTrue(core.burst.sparks.isNotEmpty())
        assertTrue(core.burst.shake > 0f)
        assertTrue(core.burst.flash > 0f)
    }

    @Test
    fun idleBandsKeepTheMaxBounded() {
        val core = KleeampCore(24)
        repeat(30) { core.push(VisMath.idleBands(24, it * 0.05), 1f / 60f) }
        for (level in core.barLevels) assertTrue(level in 0f..1f)
        assertTrue(core.bass in 0f..1f)
    }
}
