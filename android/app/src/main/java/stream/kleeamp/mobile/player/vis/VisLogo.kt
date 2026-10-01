package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.drawText
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * CLIAMP in pixel letters, ported from cliamp's renderLogo
 * (`ui/vis_logo.go`): glyphs scale to 75% of the panel height for bounce
 * headroom, each letter rides its own band with an energy bounce plus a
 * traveling wave, dots gate by energy, rows tier by height like specWrap.
 * Dots stamp straight into braille cells, matching a grid render exactly.
 */
private const val BANDS = 10

@Composable
internal fun VisLogo(frame: LogoFrame, modifier: Modifier) {
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
        val bands = VisMath.resampleAverage(frame.bands, BANDS)
        val f = frame.frame.toLong()

        // Scale letters to 75% height for bounce headroom, centered
        // vertically; letter columns justify across the full width.
        val scaleX = LogoCore.scaleX(dotCols)
        val scaleY = maxOf(1, (dotRows * 3 / 4) / LogoCore.LETTER_H)
        val step = LogoCore.columnStep(dotCols)
        val baseOffsetY = (dotRows - LogoCore.LETTER_H * scaleY) / 2

        val cells = IntArray(charCols * rows)
        for (li in 0 until LogoCore.LETTERS) {
            val energy = bands.getOrElse(LogoCore.letterBand[li]) { 0f }.coerceIn(0f, 1f)
            val fill = LogoCore.fill(energy)
            val letterX = li * step
            val letterY = baseOffsetY - LogoCore.bounce(energy, baseOffsetY, li, f)
            for (py in 0 until LogoCore.LETTER_H) {
                for (px in 0 until LogoCore.LETTER_W) {
                    if (!LogoCore.pixelSet(li, px, py)) continue
                    for (sy in 0 until scaleY) {
                        for (sx in 0 until scaleX) {
                            val dx = letterX + px * scaleX + sx
                            val dy = letterY + py * scaleY + sy
                            if (dx < 0 || dx >= dotCols || dy < 0 || dy >= dotRows) continue
                            if (!LogoCore.dotKept(li, px * scaleX + sx, py * scaleY + sy, f, fill)) continue
                            val ch = dx / 2
                            val row = dy / 4
                            cells[row * charCols + ch] = cells[row * charCols + ch] or
                                BRAILLE_BIT[dy % 4][dx % 2]
                        }
                    }
                }
            }
        }

        for (row in 0 until rows) {
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
