package stream.kleeamp.mobile.ui.components.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import stream.kleeamp.mobile.data.visualizer.WaveCore
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * The oscilloscope, ported from cliamp's braille wave (`ui/vis_wave.go`):
 * raw time-domain samples downsampled to one y-position per dot column,
 * consecutive points connected, drawn as braille cells exactly like the
 * terminal original - each glyph covers a 2x4 dot grid. Silence (and pause)
 * is the flat dotted center line.
 */
@Composable
internal fun VisWave(frame: WaveFrame, modifier: Modifier) {
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer(cacheSize = 256)
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        // Cell geometry from the measured glyph so braille cells tile the
        // frame without gaps or overlap on any density.
        val probe = measurer.measure(BRAILLE_FULL, waveStyle(10.sp))
        val cellW = probe.size.width.toFloat().coerceAtLeast(1f)
        val cellH = probe.size.height.toFloat().coerceAtLeast(1f)
        val charCols = (size.width / cellW).toInt().coerceAtLeast(1)
        val rows = (size.height / cellH).toInt().coerceAtLeast(1)
        val dotRows = rows * 4
        val dotCols = charCols * 2

        // One y per dot column, nearest downsample like the Go version.
        val trace = WaveCore.trace(frame.samples, dotCols)
        val ypos = IntArray(dotCols) { x ->
            (trace[x] * (dotRows - 1)).toInt().coerceIn(0, dotRows - 1)
        }

        val style = waveStyle(cellH.toSp() * 0.9f)
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
                val layout = measurer.measure(braille.toChar().toString(), style)
                drawText(
                    textLayoutResult = layout,
                    color = p.accent,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        ch * cellW + (cellW - layout.size.width) / 2f,
                        row * cellH + (cellH - layout.size.height) / 2f,
                    ),
                )
            }
        }
    }
}

private const val BRAILLE_BASE = 0x2800
private const val BRAILLE_FULL = "\u28FF"

/** cliamp's brailleBit table: (row, col) in a 4x2 grid to its bit value. */
private val BRAILLE_BIT = arrayOf(
    intArrayOf(0x01, 0x08),
    intArrayOf(0x02, 0x10),
    intArrayOf(0x04, 0x20),
    intArrayOf(0x40, 0x80),
)

private fun waveStyle(fontSize: TextUnit) = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = fontSize,
)
