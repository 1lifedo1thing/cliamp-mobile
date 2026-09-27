package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisColumns(frame: ColumnsFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.height <= 0f) return@Canvas
        val count = 56
        val levels = ColumnsCore.columns(frame.bands, count)
        val cellW = size.width / count
        val barW = cellW * 0.52f
        val segments = 8
        for (col in levels.indices) {
            val level = levels[col].coerceIn(0f, 1f)
            if (level <= 0.01f) continue
            val full = (level * segments).toInt().coerceIn(0, segments)
            val frac = ColumnsCore.topFrac(level, segments)
            val segH = size.height / segments
            val x = col * cellW + (cellW - barW) / 2f
            for (s in 0 until full) {
                val norm = (s + 1f) / segments
                drawRect(
                    visTier(p, VisMath.tier(norm)),
                    topLeft = Offset(x, size.height - (s + 1f) * segH),
                    size = Size(barW.coerceAtLeast(0.5f), segH * 0.92f),
                )
            }
            if (full < segments && frac > 0.05f) {
                drawRect(
                    visTier(p, VisMath.tier(level)),
                    topLeft = Offset(x, size.height - (full + frac) * segH),
                    size = Size(barW.coerceAtLeast(0.5f), (segH * frac).coerceAtLeast(0.5f)),
                )
            }
        }
    }
}
