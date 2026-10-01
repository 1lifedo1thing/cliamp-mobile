package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.drawText
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * Braille-dot bars, ported from cliamp's renderBarsDot
 * (`ui/vis_bars_dot.go`): each cell is a 2x4 braille grid whose dots fill
 * bottom-up proportionally to the band level - a stippled texture instead
 * of solid blocks. Bands take proportional widths with gaps; rows color by
 * height tier like the terminal original.
 */
private const val BANDS = 10

@Composable
internal fun VisBarsDot(frame: BarsDotFrame, modifier: Modifier) {
    val p = LocalPalette.current
    val grid = rememberBrailleGrid()
    val cellW = grid.cellW
    val cellH = grid.cellH
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val bands = VisMath.resampleAverage(frame.bands, BANDS)
        val charCols = (size.width / cellW).toInt().coerceAtLeast(1)
        val rows = (size.height / cellH).toInt().coerceAtLeast(1)
        val dotRows = rows * 4
        val widths = bandWidths(bands.size, charCols)

        var col = 0
        for (b in bands.indices) {
            val level = bands[b].coerceIn(0f, 1f)
            repeat(widths[b]) {
                if (col >= charCols) return@Canvas
                val baseX = col * cellW
                for (row in 0 until rows) {
                    var braille = BRAILLE_BASE
                    val dotRowStart = row * 4
                    for (dc in 0 until 2) {
                        for (dr in 0 until 4) {
                            // Inverted: bars grow from the bottom.
                            if (BarsDotCore.dotLit(dotRows, dotRowStart + dr, level)) {
                                braille += BRAILLE_BIT[dr][dc]
                            }
                        }
                    }
                    if (braille == BRAILLE_BASE) continue
                    // Row tier by height norm, exactly like specTag(norm).
                    val norm = (rows - 1 - row).toFloat() / rows
                    val layout = grid.layoutOf(braille)
                    drawText(
                        textLayoutResult = layout,
                        color = visTier(p, VisMath.tier(norm)),
                        topLeft = Offset(
                            baseX + (cellW - layout.size.width) / 2f,
                            row * cellH + (cellH - layout.size.height) / 2f,
                        ),
                    )
                }
                col++
            }
            // Gap between bands inherits nothing: skipped like ' '.
            if (b < bands.size - 1) col++
        }
    }
}
