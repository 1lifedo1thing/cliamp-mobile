package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * Streaming 0s and 1s, ported from cliamp's renderBinary
 * (`ui/vis_binary.go`): per-band columns with proportional widths and gaps,
 * scrolling at energy-proportional speeds, denser and brighter 1s on loud
 * bands. Row count matches cliamp's terminal rows so glyphs stay legible
 * instead of shrinking into noise on small meters.
 *
 * Only two glyphs exist per size; both are shaped once into a small bounded
 * cache - the old version measured hundreds of glyphs per frame.
 */
private const val ROWS = 7

/** cliamp analyzes DefaultSpectrumBands for binary. */
private const val BANDS = 10

@Composable
internal fun VisBinary(frame: BinaryFrame, modifier: Modifier) {
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // "bit@pixel-height" entries; a resize retires old sizes via the cap,
    // density/dir keys rebuild them on font-scale change.
    val glyphs = remember(density, layoutDirection) { LinkedHashMap<String, TextLayoutResult>() }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val bands = VisMath.resampleAverage(frame.bands, BANDS)
        val cellH = size.height / ROWS
        if (cellH <= 0f) return@Canvas
        // Glyphs fill their row: cliamp dedicates a full text row per line.
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = cellH.toSp() * 0.9f)
        fun glyph(bit: String): TextLayoutResult =
            glyphs.getOrPut(bit + "@" + cellH.toInt()) {
                if (glyphs.size > 12) glyphs.clear()
                measurer.measure(
                    bit,
                    style,
                    constraints = Constraints(),
                    density = density,
                    layoutDirection = layoutDirection,
                )
            }
        val zero = glyph("0")
        val one = glyph("1")
        val cellW = maxOf(zero.size.width, one.size.width).toFloat().coerceAtLeast(1f)
        val charCols = (size.width / cellW).toInt().coerceAtLeast(1)
        val widths = bandWidths(bands.size, charCols)
        val f = frame.frame.toLong()

        var col = 0
        for (b in bands.indices) {
            val energy = bands[b].coerceIn(0f, 1f)
            val scroll = (f / BinaryCore.speed(energy)).toInt()
            repeat(widths[b]) {
                if (col >= charCols) return@Canvas
                for (row in 0 until ROWS) {
                    val bit = BinaryCore.bit(b, row, col, scroll, energy)
                    val layout = if (bit) one else zero
                    drawText(
                        textLayoutResult = layout,
                        color = visTier(p, BinaryCore.tier(bit, energy)),
                        topLeft = Offset(
                            col * cellW + (cellW - layout.size.width) / 2f,
                            row * cellH + (cellH - layout.size.height) / 2f,
                        ),
                    )
                }
                col++
            }
            // Single-space inter-band gap, like the terminal original.
            if (b < bands.size - 1) col++
        }
    }
}

/**
 * Proportional band widths with gaps, ported from cliamp's visBandWidth:
 * visible bands split the columns, leftovers distribute one extra each.
 */
internal fun bandWidths(totalBands: Int, charCols: Int): IntArray {
    if (totalBands <= 0 || charCols <= 0) return IntArray(0)
    val visible = minOf(totalBands, charCols)
    val gapCount = minOf(visible - 1, maxOf(0, charCols - visible)).coerceAtLeast(0)
    val bandCols = charCols - gapCount
    val base = bandCols / visible
    val extra = bandCols % visible
    return IntArray(totalBands) { b ->
        if (b >= visible) 0 else base + if (b < extra) 1 else 0
    }
}
