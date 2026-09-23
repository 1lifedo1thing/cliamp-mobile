package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MeterCore must smooth with cliamp's attack/release rates (34/s up, 10/s
 * down) against real frame dt. Fixed per-frame fractions would run twice as
 * hot on 120 Hz screens, making every transient overshoot its beat.
 */
class MeterCoreTest {

    @Test
    fun attackRateMatchesCliamp() {
        val core = MeterCore(4)
        core.push(FloatArray(64) { 1f }, 0.03f)
        // 1 - e^(-34 * 0.03) ~= 0.64 from a ~0.05 rest.
        assertTrue("level ${core.levels[0]} not near 0.64", core.levels[0] > 0.55f)
        assertTrue("level ${core.levels[0]} not near 0.64", core.levels[0] < 0.75f)
    }

    @Test
    fun subdivisionDoesNotChangeTheCurve() {
        val single = MeterCore(4)
        val split = MeterCore(4)
        val loud = FloatArray(64) { 1f }
        single.push(loud, 1f / 30f)
        split.push(loud, 1f / 60f)
        split.push(loud, 1f / 60f)
        for (i in 0 until 4) {
            assertEquals(single.levels[i], split.levels[i], 0.02f)
        }
    }

    @Test
    fun releaseIsSlowerThanAttack() {
        val core = MeterCore(4)
        repeat(120) { core.push(FloatArray(64) { 1f }, 1f / 60f) }
        val top = core.levels[0]
        core.push(FloatArray(64), 1f / 60f)
        val fallStep = top - core.levels[0]
        // One 60fps release step at rate 10 from ~1.0 is ~0.15; an attack
        // step at rate 34 would be ~0.43. Release must stay well under that.
        assertTrue("release step $fallStep too aggressive", fallStep < 0.25f)
        assertTrue("release step $fallStep stalled", fallStep > 0.05f)
    }
}
