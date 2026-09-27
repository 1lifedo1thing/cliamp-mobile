package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisLogo(frame: LogoFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val bands = VisMath.resampleAverage(frame.bands, frame.columns.coerceAtLeast(1))
        // Fit the word: cell pitch from the tighter axis.
        val pitchX = size.width / LogoCore.TOTAL_W
        val pitchY = size.height / LogoCore.LETTER_H
        val pitch = minOf(pitchX, pitchY)
        if (pitch <= 0f) return@Canvas
        val ox = (size.width - pitch * LogoCore.TOTAL_W) / 2f
        val oy = (size.height - pitch * LogoCore.LETTER_H) / 2f
        val r = pitch * 0.30f
        for (gx in 0 until LogoCore.TOTAL_W) {
            val level = bands.getOrElse(gx * bands.size / LogoCore.TOTAL_W) { 0f }
            for (gy in 0 until LogoCore.LETTER_H) {
                if (!LogoCore.dotOn(gx, gy, level, frame.frame.toLong())) continue
                drawCircle(
                    visTier(p, VisMath.tier(level)),
                    r.coerceAtLeast(0.5f),
                    Offset(ox + pitch * (gx + 0.5f), oy + pitch * (gy + 0.5f)),
                )
            }
        }
    }
}
