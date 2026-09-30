package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.drawText
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * Doom-fire, ported from cliamp's flameDriver render (`ui/vis_flame.go`):
 * the heat buffer (row 0 = bottom source) flips vertically onto braille
 * cells; wispy tips below 0.25 heat cull stochastically so the upper edge
 * breaks softly instead of cutting hard. Hot cores read yellow, bodies red.
 *
 * Like cliamp's Render, the grid is ensured from canvas dimensions.
 */
@Composable
internal fun VisFlame(frame: FlameFrame, modifier: Modifier) {
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
        val f = frame.frame.toLong()

        for (row in 0 until rows) {
            val dotRowStart = row * 4
            for (ch in 0 until charCols) {
                var braille = BRAILLE_BASE
                var tier = -1
                val dotColStart = ch * 2
                for (dc in 0 until 2) {
                    val x = dotColStart + dc
                    if (x >= core.dotCols) continue
                    for (dr in 0 until 4) {
                        val y = dotRowStart + dr
                        if (y >= core.dotRows) continue
                        // Panel y=0 is top; heat y=0 is the bottom source.
                        val h = core.heatAt(x, core.dotRows - 1 - y)
                        if (h < 0.10f) continue
                        if (h < 0.25f &&
                            VisMath.scatterHash(0, y, x, f) > h * 4
                        ) {
                            continue
                        }
                        braille += BRAILLE_BIT[dr][dc]
                        // Hottest reads yellow, body red.
                        val t = if (h >= 0.55f) 1 else 2
                        if (t > tier) tier = t
                    }
                }
                if (braille == BRAILLE_BASE) continue
                val layout = grid.layoutOf(braille)
                drawText(
                    textLayoutResult = layout,
                    color = visTier(p, tier.coerceIn(0, 2)),
                    topLeft = Offset(
                        ch * cellW + (cellW - layout.size.width) / 2f,
                        row * cellH + (cellH - layout.size.height) / 2f,
                    ),
                )
            }
        }
    }
}
