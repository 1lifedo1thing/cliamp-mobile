package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.player.vis.VisMath
import stream.kleeamp.mobile.theme.LocalPalette
import kotlin.math.roundToInt

@Composable
internal fun VisStereo(frame: StereoFrame, modifier: Modifier) {
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer(cacheSize = 8)
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val rowH = size.height / 2f
        val cells = maxOf(8, frame.columns * 2)
        val labelW = 14.dp.toPx()
        val cellArea = size.width - labelW
        if (cellArea <= 0f || rowH <= 0f) return@Canvas
        val cellW = cellArea / cells
        val cellH = rowH * 0.45f
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = rowH.toSp() * 0.5f)

        fun channel(index: Int, label: String, level: Float, peak: Float) {
            val top = index * rowH
            val layout = measurer.measure(label, style)
            drawText(
                textLayoutResult = layout,
                color = p.inkTertiary,
                topLeft = Offset(0f, top + (rowH - layout.size.height) / 2f),
            )
            val lit = (level.coerceIn(0f, 1f) * cells).roundToInt().coerceIn(0, cells)
            val peakCell = if (peak > 0f) {
                (peak.coerceIn(0f, 1f) * cells).roundToInt().coerceIn(1, cells) - 1
            } else {
                -1
            }
            for (cell in 0 until cells) {
                val color = when {
                    cell == peakCell -> p.peak
                    cell < lit -> visTier(p, VisMath.tier(cell.toFloat() / maxOf(1, cells - 1)))
                    else -> p.unlit
                }
                drawRect(
                    color = color,
                    topLeft = Offset(labelW + cell * cellW, top + (rowH - cellH) / 2f),
                    size = Size((cellW - 1f).coerceAtLeast(0.5f), cellH),
                )
            }
        }

        channel(0, "L", frame.levels[0], frame.peaks[0])
        channel(1, "R", frame.levels[1], frame.peaks[1])
    }
}
