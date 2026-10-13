package stream.kleeamp.mobile.player

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientColorTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun solidRedAveragesRed() {
        val avg = ambientAverage(IntArray(64) { argb(200, 20, 20) })
        assertTrue(avg.red > 0.7f)
        assertTrue(avg.green < 0.15f)
        assertTrue(avg.blue < 0.15f)
    }

    @Test
    fun grayCoverFallsBackToAccent() {
        val gray = ambientAverage(IntArray(64) { argb(128, 128, 128) })
        val accent = Color(0.2f, 0.5f, 0.9f)
        val ground = Color(0.05f, 0.05f, 0.06f)
        val fromGray = ambientTarget(gray, accent, ground, dark = true)
        val fromNull = ambientTarget(null, accent, ground, dark = true)
        assertEquals(fromNull.red, fromGray.red, 0.001f)
        assertEquals(fromNull.green, fromGray.green, 0.001f)
        assertEquals(fromNull.blue, fromGray.blue, 0.001f)
    }

    @Test
    fun artHueSurvivesSeating() {
        val blue = Color(0.1f, 0.2f, 0.9f)
        val accent = Color(0.9f, 0.5f, 0.1f)
        val ground = Color(0.05f, 0.05f, 0.06f)
        val seated = ambientTarget(blue, accent, ground, dark = true)
        // Still blue-dominant, not dragged to the orange accent.
        assertTrue(seated.blue > seated.red)
        assertTrue(seated.blue > seated.green)
    }

    @Test
    fun darkSeatingIsDeeperThanLight() {
        val art = Color(0.7f, 0.2f, 0.2f)
        val accent = Color(0.2f, 0.5f, 0.9f)
        val darkGround = Color(0.05f, 0.05f, 0.06f)
        val lightGround = Color(0.95f, 0.94f, 0.90f)
        val dark = ambientTarget(art, accent, darkGround, dark = true)
        val light = ambientTarget(art, accent, lightGround, dark = false)
        val darkLuma = 0.2126f * dark.red + 0.7152f * dark.green + 0.0722f * dark.blue
        val lightLuma = 0.2126f * light.red + 0.7152f * light.green + 0.0722f * light.blue
        assertTrue(darkLuma < lightLuma)
    }

    @Test
    fun spectrumEnergyIgnoresEmptiness() {
        assertEquals(0f, spectrumEnergy(null), 0f)
        assertEquals(0f, spectrumEnergy(FloatArray(0)), 0f)
        assertEquals(0f, spectrumEnergy(FloatArray(64)), 0f)
    }

    @Test
    fun spectrumEnergyScalesAndClamps() {
        assertEquals(1f, spectrumEnergy(FloatArray(64) { 1f }), 0.001f)
        // Half-scale lows read strong but not full.
        assertEquals(0.8f, spectrumEnergy(FloatArray(64) { 0.5f }), 0.001f)
        // Hotter-than-full input never escapes 0..1.
        assertEquals(1f, spectrumEnergy(FloatArray(64) { 4f }), 0.001f)
    }

    @Test
    fun hsvRoundTripKeepsPrimaryHues() {        listOf(Color.Red, Color.Green, Color.Blue).forEach { c ->
            val back = hsvToRgb(rgbToHsv(c)!!)
            assertEquals(c.red, back.red, 0.01f)
            assertEquals(c.green, back.green, 0.01f)
            assertEquals(c.blue, back.blue, 0.01f)
        }
    }
}
