package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.drawText
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * The oscilloscope, ported from cliamp's braille wave (`ui/vis_wave.go`):
 * raw time-domain samples downsampled to one y-position per dot column,
 * consecutive points connected, drawn as braille cells exactly like the
 * terminal original - each glyph covers a 2x4 dot grid. Silence (and pause)
 * is the flat dotted center line.
 *
 * Frame cost control lives in [rememberBrailleGrid]: probe measured once,
 * each distinct glyph shaped once, frames only do integer grid math plus
 * draws. Measuring + shaping hundreds of glyphs per frame is what used to
 * drop the scope to a stutter, worst on slow music where every frame
 * differs slightly and nothing ever settled.
 */
@Composable
internal fun VisWave(frame: WaveFrame, modifier: Modifier) {
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

        // One y per dot column, nearest downsample like the Go version.
        val trace = WaveCore.trace(frame.samples, dotCols)
        val ypos = IntArray(dotCols) { x ->
            (trace[x] * (dotRows - 1)).toInt().coerceIn(0, dotRows - 1)
        }

        for (row in 0 until rows) {
            val dotRowStart = row * 4
            for (ch in 0 until charCols) {
                var braille = BRAILLE_BASE
                val dotColStart = ch * 2
                for (dc in 0 until 2) {
                    val x = dotColStart + dc
                    if (x >= dotCols) continue
                    val y = ypos[x]
                    // Connect to the previous point so the trace is
                    // continuous, exactly like renderWave.
                    val prevY = if (x > 0) ypos[x - 1] else y
                    val yMin = minOf(y, prevY)
                    val yMax = maxOf(y, prevY)
                    for (dr in 0 until 4) {
                        val dotY = dotRowStart + dr
                        if (dotY in yMin..yMax) {
                            braille += BRAILLE_BIT[dr][dc]
                        }
                    }
                }
                if (braille == BRAILLE_BASE) continue
                val layout = grid.layoutOf(braille)
                drawText(
                    textLayoutResult = layout,
                    color = p.accent,
                    topLeft = Offset(
                        ch * cellW + (cellW - layout.size.width) / 2f,
                        row * cellH + (cellH - layout.size.height) / 2f,
                    ),
                )
            }
        }
    }
}
