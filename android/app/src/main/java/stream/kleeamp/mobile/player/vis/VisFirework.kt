package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.drawText
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * Firework bursts, ported from cliamp's renderFirework
 * (`ui/vis_firework.go`): bursts rise with a trail then explode into
 * jittered, gravity-dragged, stochastically fading particles on a braille
 * grid. Rows color by height tier (top bright like a night sky).
 * Dots stamp straight into braille cells, matching a grid render exactly.
 */
@Composable
internal fun VisFirework(frame: FireworkFrame, modifier: Modifier) {
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
        val bands = VisMath.resampleAverage(frame.bands, 10)

        var energy = 0f
        for (b in bands) energy += b
        val avg = if (bands.isEmpty()) 0f else energy / bands.size

        val cells = IntArray(charCols * rows)
        repeat(FireworkCore.burstCount(avg)) { burst ->
            for ((x, y) in FireworkCore.dots(burst, f, dotCols, dotRows, bands)) {
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
