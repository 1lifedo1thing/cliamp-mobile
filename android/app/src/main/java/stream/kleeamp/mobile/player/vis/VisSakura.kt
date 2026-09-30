package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.drawText
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * Cherry blossoms, ported from cliamp's renderSakura (`ui/vis_sakura.go`):
 * braille-silhouette petals drifting down with per-petal fall speed and
 * sway, sparse in the quiet and full when loud. Rows color by height tier
 * like specWrap. Dots stamp straight into braille cells (no grid buffer):
 * each dot ORs its cell bit, exactly matching a grid render.
 */
@Composable
internal fun VisSakura(frame: SakuraFrame, modifier: Modifier) {
    val p = LocalPalette.current
    val grid = rememberBrailleGrid()
    val cellW = grid.cellW
    val cellH = grid.cellH
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val charCols = (size.width / cellW).toInt().coerceAtLeast(1)
        val rows = (size.height / cellH).toInt().coerceAtLeast(1)
        val dotRows = rows * 4
        val dotCols = charCols * 2
        if (dotRows < 4 || dotCols < 4) return@Canvas
        val f = frame.frame.toLong()

        var energy = 0f
        for (b in frame.bands) energy += b
        val avg = if (frame.bands.isEmpty()) 0f else energy / frame.bands.size

        // Cell accumulators: braille bits per character cell.
        val cells = IntArray(charCols * rows)
        repeat(SakuraCore.petalCount(avg)) { petal ->
            for ((x, y) in SakuraCore.dots(petal, f, dotCols, dotRows)) {
                if (x < 0 || x >= dotCols || y < 0 || y >= dotRows) continue
                val ch = x / 2
                val row = y / 4
                cells[row * charCols + ch] = cells[row * charCols + ch] or
                    BRAILLE_BIT[y % 4][x % 2]
            }
        }

        for (row in 0 until rows) {
            // Top rows bright, bottom dimmer, like specWrap.
            val norm = (rows - 1 - row).toFloat() / rows
            val color = visTier(p, VisMath.tier(norm))
            for (ch in 0 until charCols) {
                val bits = cells[row * charCols + ch]
                if (bits == 0) continue
                val layout = grid.layoutOf(BRAILLE_BASE + bits)
                drawText(
                    textLayoutResult = layout,
                    color = color,
                    topLeft = Offset(
                        ch * cellW + (cellW - layout.size.width) / 2f,
                        row * cellH + (cellH - layout.size.height) / 2f,
                    ),
                )
            }
        }
    }
}
