package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import stream.kleeamp.mobile.theme.KleeampPalette
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * Wireframe equalizer, ported from cliamp's redSectorDriver
 * (`ui/vis_red_sector.go`): five hollow bars on a ground line tumble as a
 * rigid body with backface-culled faces over a drifting starfield, drawn as
 * braille. Cells keep the highest tag, so bars (5..7) always win over stars
 * (1..4). Like cliamp's Render, the grid is ensured from canvas dimensions.
 */
@Composable
internal fun VisRedSector(frame: RedSectorFrame, modifier: Modifier) {
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
        if (dotRows < 4 || dotCols < 16) return@Canvas
        val core = frame.core
        core.ensure(dotRows, dotCols)
        core.draw(frame.frame.toLong())

        for (row in 0 until rows) {
            val dotRowStart = row * 4
            for (ch in 0 until charCols) {
                var braille = BRAILLE_BASE
                var tag = 0
                val dotColStart = ch * 2
                for (dc in 0 until 2) {
                    val x = dotColStart + dc
                    if (x >= core.dotCols) continue
                    for (dr in 0 until 4) {
                        val y = dotRowStart + dr
                        if (y >= core.dotRows) continue
                        val t = core.tagAt(x, y)
                        if (t == 0) continue
                        braille += BRAILLE_BIT[dr][dc]
                        if (t > tag) tag = t
                    }
                }
                // Blank braille paints nothing, so empty cells are skipped.
                if (braille == BRAILLE_BASE) continue
                val layout = grid.layoutOf(braille)
                drawText(
                    textLayoutResult = layout,
                    color = redSectorColor(p, tag),
                    topLeft = Offset(
                        ch * cellW + (cellW - layout.size.width) / 2f,
                        row * cellH + (cellH - layout.size.height) / 2f,
                    ),
                )
            }
        }
    }
}

/**
 * Seven-tag palette: the four star tags sit below the three bar tags.
 * Stars read dim, bars read the spectrum tiers - the contrast between the
 * dim field and the vector object that the homage is built on.
 */
private fun redSectorColor(p: KleeampPalette, tag: Int): Color =
    when {
        tag >= RedSectorCore.TAG_HIGH -> visTier(p, 2)
        tag >= RedSectorCore.TAG_MID -> visTier(p, 1)
        tag >= RedSectorCore.TAG_LOW -> visTier(p, 0)
        else -> p.inkFaint.copy(alpha = 0.30f + 0.12f * tag)
    }
