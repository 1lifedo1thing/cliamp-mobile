package stream.kleeamp.mobile.ui.components.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import stream.kleeamp.mobile.data.visualizer.MatrixCore
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisMatrix(frame: MatrixFrame, modifier: Modifier) {
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer(cacheSize = 128)
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val cols = frame.columns
        if (cols == 0) return@Canvas
        val cellW = size.width / cols
        val cellH = cellW * 1.3f
        if (cellH <= 0f) return@Canvas
        val rows = maxOf(1, (size.height / cellH).toInt())
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = cellH.toSp() * 0.72f)

        for (col in 0 until cols) {
            val energy = frame.energy.getOrElse(col) { 0f }
            for (row in 0 until rows) {
                val cell = MatrixCore.cell(energy, col, row, rows, frame.frame.toLong()) ?: continue
                val layout = measurer.measure(cell.char.toString(), style)
                drawText(
                    textLayoutResult = layout,
                    color = visTier(p, cell.tier),
                    topLeft = Offset(
                        col * cellW + (cellW - layout.size.width) / 2f,
                        row * cellH + (cellH - layout.size.height) / 2f,
                    ),
                )
            }
        }
    }
}
