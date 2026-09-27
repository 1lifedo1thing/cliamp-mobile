package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisScatter(frame: ScatterFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val cols = 40
        val rows = 20
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val bands = VisMath.resampleAverage(frame.bands, frame.columns.coerceAtLeast(1))
        val f = frame.frame.toLong()
        val cellW = size.width / cols
        val cellH = size.height / rows
        val r = minOf(cellW, cellH) * 0.30f
        for (cx in 0 until cols) {
            val band = (cx * bands.size / cols).coerceIn(0, bands.size - 1)
            val level = bands.getOrElse(band) { 0f }
            if (level <= 0.03f) continue
            for (ry in 0 until rows) {
                if (!ScatterCore.lit(band, cx, ry, rows, f, level)) continue
                val norm = 1f - ry.toFloat() / rows
                drawCircle(
                    visTier(p, VisMath.tier(norm)),
                    r.coerceAtLeast(0.5f),
                    Offset(cellW * (cx + 0.5f), cellH * (ry + 0.5f)),
                )
            }
        }
    }
}
