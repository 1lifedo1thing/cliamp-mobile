package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * The oscilloscope, ported from cliamp's braille wave (`ui/vis_wave.go`):
 * raw time-domain samples downsampled to one y-position per dot column,
 * consecutive points connected, drawn as braille cells exactly like the
 * terminal original - each glyph covers a 2x4 dot grid. Silence (and pause)
 * is the flat dotted center line.
 *
 * Frame cost control: the cell probe is measured once per density (it only
 * depends on font scale, never on canvas size), and each distinct braille
 * glyph is shaped once and cached - a frame then only does integer grid
 * math plus draws. Measuring + shaping hundreds of glyphs per frame is
 * what used to drop the scope to a stutter, worst on slow music where
 * every frame differs slightly and nothing ever settled.
 */
@Composable
internal fun VisWave(frame: WaveFrame, modifier: Modifier) {
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // Fixed style for every glyph: the probe and all cached layouts share
    // it, so tiling stays exact and the cache never invalidates on size.
    val style = waveStyle(10.sp)
    val probe = remember(measurer, density, layoutDirection) {
        measurer.measure(
            BRAILLE_FULL,
            style,
            constraints = Constraints(),
            density = density,
            layoutDirection = layoutDirection,
        )
    }
    val cellW = probe.size.width.toFloat().coerceAtLeast(1f)
    val cellH = probe.size.height.toFloat().coerceAtLeast(1f)
    // At most the 256 braille patterns this grid can emit, each shaped once.
    val glyphCache = remember { HashMap<Int, androidx.compose.ui.text.TextLayoutResult>(256) }
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
                val layout = glyphCache.getOrPut(braille) {
                    measurer.measure(
                        braille.toChar().toString(),
                        style,
                        constraints = Constraints(),
                        density = density,
                        layoutDirection = layoutDirection,
                    )
                }
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
