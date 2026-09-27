package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisAscii(frame: AsciiFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val cols = 48
        if (size.height <= 0f) return@Canvas
        val bands = VisMath.resampleAverage(frame.bands, cols)
        val cellW = size.width / cols
        val rows = 12
        val cellH = size.height / rows
        for (col in bands.indices) {
            val level = bands[col].coerceIn(0f, 1f)
            val full = (level * rows).toInt().coerceIn(0, rows)
            val x = col * cellW + cellW * 0.24f
            val w = (cellW * 0.52f).coerceAtLeast(0.5f)
            for (r in 0 until full) {
                val norm = (r + 1f) / rows
                drawRect(
                    visTier(p, VisMath.tier(norm)),
                    topLeft = Offset(x, size.height - (r + 1f) * cellH + cellH * 0.08f),
                    size = Size(w, cellH * 0.84f),
                )
            }
            // Partial cap block with the shade step as alpha.
            val shade = AsciiCore.shade(level, full.toFloat() / rows, (full + 1f) / rows)
            if (full < rows && shade > 0) {
                drawRect(
                    visTier(p, VisMath.tier(level)).copy(alpha = 0.25f + 0.2f * shade),
                    topLeft = Offset(x, size.height - (full + 1f) * cellH + cellH * 0.08f),
                    size = Size(w, cellH * 0.84f),
                )
            }
        }
    }
}
