package stream.kleeamp.mobile.player.vis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

internal const val BRAILLE_BASE = 0x2800
internal const val BRAILLE_FULL = "\u28FF"

/**
 * Glyph oversize vs the tile grid: phone monospace fonts draw small dots
 * with wide pitch, so glyphs draw larger and centered on their tile while
 * positions stay on the probe grid. Dots grow into each other like terminal
 * braille, geometry still matches cliamp exactly.
 */
internal const val GLYPH_SCALE = 1.35f

/** cliamp's brailleBit table: (row, col) in a 4x2 grid to its bit value. */
internal val BRAILLE_BIT = arrayOf(
    intArrayOf(0x01, 0x08),
    intArrayOf(0x02, 0x10),
    intArrayOf(0x04, 0x20),
    intArrayOf(0x40, 0x80),
)

internal fun brailleStyle(fontSize: TextUnit) = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = fontSize,
)

/**
 * Shared braille tile grid for the dot-matrix renderers (wave, heartbeat):
 * the cell probe is measured once per density (it only depends on font
 * scale, never on canvas size), and each distinct glyph is shaped once and
 * cached - a frame then only does integer grid math plus draws.
 */
internal class BrailleGrid(
    val cellW: Float,
    val cellH: Float,
    private val measure: (Int) -> TextLayoutResult,
) {
    fun layoutOf(braille: Int): TextLayoutResult = measure(braille)
}

/**
 * @param scale glyph oversize vs the tile grid. Traces use [GLYPH_SCALE] so
 * dots grow into each other; particle fields like sand use 1.0 so isolated
 * grains stay grain-sized while piles still merge.
 */
@Composable
internal fun rememberBrailleGrid(scale: Float = GLYPH_SCALE): BrailleGrid {
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val drawStyle = brailleStyle(10.sp * scale)
    val probe = remember(measurer, density, layoutDirection) {
        measurer.measure(
            BRAILLE_FULL,
            brailleStyle(10.sp),
            constraints = Constraints(),
            density = density,
            layoutDirection = layoutDirection,
        ).size
    }
    val cellW = probe.width.toFloat().coerceAtLeast(1f)
    val cellH = probe.height.toFloat().coerceAtLeast(1f)
    // At most the 256 braille patterns a grid can emit, each shaped once.
    val glyphCache = remember { HashMap<Int, TextLayoutResult>(256) }
    return remember(cellW, cellH) {
        BrailleGrid(cellW, cellH) { braille ->
            glyphCache.getOrPut(braille) {
                measurer.measure(
                    braille.toChar().toString(),
                    drawStyle,
                    constraints = Constraints(),
                    density = density,
                    layoutDirection = layoutDirection,
                )
            }
        }
    }
}
