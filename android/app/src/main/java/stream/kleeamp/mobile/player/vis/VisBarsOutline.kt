package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * Bar peak outlines, ported from cliamp's renderBarsOutline
 * (`ui/vis_bars_outline.go`): only the row containing each bar's peak draws
 * a horizontal line, everything else stays empty - a minimal line-graph.
 * Bands take proportional widths with gaps; the line colors by its row's
 * height tier like specWrap(rowBottom).
 */
private const val BANDS = 10

/** Minimum column/row pixel sizes so peaks stay crisp on small meters. */
private const val MIN_COL_PX = 24f
private const val MIN_ROW_PX = 26f

@Composable
internal fun VisBarsOutline(frame: BarsOutlineFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val bands = VisMath.resampleAverage(frame.bands, BANDS)
        val charCols = maxOf(bands.size, (size.width / MIN_COL_PX).toInt())
        val rows = maxOf(4, (size.height / MIN_ROW_PX).toInt())
        val colW = size.width / charCols
        val rowH = size.height / rows
        val widths = bandWidths(bands.size, charCols)

        var col = 0
        for (b in bands.indices) {
            val level = bands[b].coerceIn(0f, 1f)
            val row = BarsOutlineCore.peakRow(level, rows)
            if (row != null) {
                val rowBottom = (rows - 1 - row).toFloat() / rows
                drawLine(
                    visTier(p, VisMath.tier(rowBottom)),
                    Offset(col * colW, (row + 0.5f) * rowH),
                    Offset((col + widths[b]) * colW, (row + 0.5f) * rowH),
                    strokeWidth = (rowH * 0.22f).coerceAtLeast(1f),
                )
            }
            col += widths[b]
            // Single-space inter-band gap, like the terminal original.
            if (b < bands.size - 1) col++
        }
    }
}
