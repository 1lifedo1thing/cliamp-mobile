package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * Falling rain inside bar shapes, ported from cliamp's renderRain
 * (`ui/vis_rain.go`): bar height follows band level, but the interior is
 * animated falling drops with head/body/tail segments and coloring. Louder
 * bands rain taller and denser.
 *
 * cliamp draws ┃/│/: glyphs; the segments render here as weighted vertical
 * lines (heavy head, medium body, thin tail) because box-drawing glyphs do
 * not resolve on all Android fonts while lines always do. Layout, gate
 * rhythm, fall speed, drop lengths and tier colors follow the Go source
 * exactly; bands take proportional widths with gaps like the terminal.
 */
private const val BANDS = 10

@Composable
internal fun VisRain(frame: RainFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val bands = VisMath.resampleAverage(frame.bands, BANDS)
        // Character grid derived from a terminal-like cell: 10 bands across
        // with gaps, rows tall enough for 2-4 cell drops to read.
        val charCols = maxOf(20, (size.width / 28f).toInt())
        val rows = maxOf(8, (size.height / 30f).toInt())
        val cellW = size.width / charCols
        val cellH = size.height / rows
        val widths = bandWidths(bands.size, charCols)
        val f = frame.frame.toLong()

        var col = 0
        for (b in bands.indices) {
            val level = bands[b].coerceIn(0f, 1f)
            repeat(widths[b]) {
                if (col >= charCols) return@Canvas
                // Column gate: closed columns stay empty; energy sets density.
                val open = RainCore.active(b, col, f, level)
                if (open) {
                    val drop = RainCore.drop(col, rows)
                    val head = ((f / drop.speed).toInt() + drop.offset) % drop.cycleLen
                    for (d in 0 until drop.len) {
                        val row = head - d
                        // Above the bar: empty, like the terminal original.
                        val rowNorm = (rows - 1 - row).toDouble() / rows
                        if (row !in 0 until rows || rowNorm >= level) continue
                        val x = (col + 0.5f) * cellW
                        drawLine(
                            visTier(p, if (d == 0) 2 else if (d == 1) 1 else 0),
                            Offset(x, row * cellH),
                            Offset(x, (row + 1) * cellH),
                            // Heavy head, medium body, thin tail: ┃/│/: weights.
                            strokeWidth = (cellW * (if (d == 0) 0.5f else if (d == 1) 0.36f else 0.22f))
                                .coerceAtLeast(1f),
                        )
                    }
                }
                col++
            }
            // Single-space inter-band gap, like the terminal original.
            if (b < bands.size - 1) col++
        }
    }
}
