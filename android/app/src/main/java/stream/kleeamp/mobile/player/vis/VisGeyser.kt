package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.drawText
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * Particle fountain, ported from cliamp's geyserDriver
 * (`ui/vis_geyser.go`): the tick holds a mist column while loud and fires
 * bass-kick jets, stamping tiers onto a dot grid that draws here as braille
 * cells, exactly like the terminal original. Tiers read green/yellow/red
 * through the shared tier ramp.
 *
 * Like cliamp's Render, the grid is ensured from canvas dimensions.
 */
@Composable
internal fun VisGeyser(frame: GeyserFrame, modifier: Modifier) {
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
