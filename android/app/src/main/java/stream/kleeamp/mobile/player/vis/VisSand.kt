package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.drawText
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * Falling sand, ported from cliamp's sandDriver (`ui/vis_sand.go`): a
 * full dot-grid automaton (pour tinted by band, bass avalanches, ballistic
 * explosion phase) drawn as braille cells with tier colors, exactly like
 * the terminal original.
 *
 * Like cliamp's Render, the grid is ensured from canvas dimensions - a
 * resize clears the bed, exactly like a terminal resize does.
 */
/** Sand grains stay grain-sized: piles merge, loners read as sand. */
private const val GRAIN_SCALE = 1.15f

@Composable
internal fun VisSand(frame: SandFrame, modifier: Modifier) {
    val p = LocalPalette.current
    val grid = rememberBrailleGrid(GRAIN_SCALE)
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
        val core = frame.core
        core.ensure(dotRows, dotCols)

        for (row in 0 until rows) {
            val dotRowStart = row * 4
            for (ch in 0 until charCols) {
                var braille = BRAILLE_BASE
                var tier = 0
                val dotColStart = ch * 2
                for (dc in 0 until 2) {
                    val x = dotColStart + dc
                    if (x >= core.dotCols) continue
                    for (dr in 0 until 4) {
                        val y = dotRowStart + dr
                        if (y >= core.dotRows) continue
                        val g = core.tierAt(x, y)
                        if (g == 0) continue
                        braille += BRAILLE_BIT[dr][dc]
                        if (g > tier) tier = g
                    }
                }
                if (braille == BRAILLE_BASE) continue
                val layout = grid.layoutOf(braille)
                drawText(
                    textLayoutResult = layout,
                    // Tiers are 1 = green, 2 = yellow, 3 = red, like cliamp.
                    color = visTier(p, (tier - 1).coerceIn(0, 2)),
                    topLeft = Offset(
                        ch * cellW + (cellW - layout.size.width) / 2f,
                        row * cellH + (cellH - layout.size.height) / 2f,
                    ),
                )
            }
        }
    }
}
