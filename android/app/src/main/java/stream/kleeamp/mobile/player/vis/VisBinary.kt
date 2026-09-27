package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisBinary(frame: BinaryFrame, modifier: Modifier) {
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer(cacheSize = 8)
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val cols = frame.columns
        if (cols == 0 || size.height <= 0f) return@Canvas
        val bands = VisMath.resampleAverage(frame.bands, cols)
        val rows = 14
        val cellW = size.width / cols
        val cellH = size.height / rows
        if (cellH <= 0f) return@Canvas
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = cellH.toSp() * 0.72f)
        val f = frame.frame.toLong()
        for (col in 0 until cols) {
            val level = bands[col].coerceIn(0f, 1f)
            for (row in 0 until rows) {
                val bit = BinaryCore.bit(col, row, rows, f, level)
                val norm = 1f - row.toFloat() / rows
                val layout = measurer.measure(bit.toString(), style)
                drawText(
                    textLayoutResult = layout,
                    color = if (bit == '1') visTier(p, VisMath.tier(norm))
                    else p.inkFaint.copy(alpha = 0.35f),
                    topLeft = Offset(
                        col * cellW + (cellW - layout.size.width) / 2f,
                        row * cellH + (cellH - layout.size.height) / 2f,
                    ),
                )
            }
        }
    }
}
