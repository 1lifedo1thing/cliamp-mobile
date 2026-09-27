package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisBarsDot(frame: BarsDotFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val cols = frame.columns
        if (cols == 0 || size.height <= 0f) return@Canvas
        val bands = VisMath.resampleAverage(frame.bands, cols)
        val dotsX = 3
        val dotsY = 12
        val cellW = size.width / cols
        val cellH = size.height / dotsY
        val dotR = minOf(cellW / dotsX, cellH) * 0.30f
        for (col in 0 until cols) {
            val lit = BarsDotCore.filled(dotsX * dotsY, bands[col])
            var left = lit
            for (dy in 0 until dotsY) {
                for (dx in 0 until dotsX) {
                    if (left <= 0) break
                    left--
                    val norm = 1f - dy.toFloat() / dotsY
                    drawCircle(
                        visTier(p, VisMath.tier(norm)),
                        dotR.coerceAtLeast(0.5f),
                        Offset(
                            col * cellW + cellW * (dx + 0.5f) / dotsX,
                            size.height - cellH * (dy + 0.5f),
                        ),
                    )
                }
            }
        }
    }
}
