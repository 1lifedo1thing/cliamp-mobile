package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * Static heatmap tiles, ported from cliamp's mosaicDriver
 * (`ui/vis_mosaic.go`): tiles never scroll, each ignites past its own
 * threshold and decays in place. Brightness runs ░▒▓█ plus hot and
 * overdrive tiers; block-element glyphs do not resolve on all Android
 * fonts, so the four shades render as alpha steps of the same tier color
 * while hot/overdrive stay solid - same ramp, no font dependency.
 *
 * Like cliamp's Render, the grid is ensured from canvas dimensions.
 */
@Composable
internal fun VisMosaic(frame: MosaicFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        // Tile unit scales with width like terminal columns do: a 100-unit
        // panel holds cliamp's 33 tiles; rows follow at text-row pitch.
        val unit = size.width / 100f
        if (unit <= 0f) return@Canvas
        val core = frame.core
        val tiles = core.tileCount(size.width, unit)
        val rows = maxOf(1, (size.height / (unit * 3f)).toInt())
        if (tiles <= 0) return@Canvas
        core.ensureGrid(rows, tiles, 10)

        val tileW = unit * 2f
        val tileH = unit * 3f
        for (r in 0 until core.rows) {
            for (c in 0 until core.tiles) {
                val (tier, alpha) = when (core.levelOf(core.value[r * core.tiles + c])) {
                    0 -> continue
                    1 -> 0 to 0.30f
                    2 -> 0 to 0.52f
                    3 -> 0 to 0.75f
                    4 -> 0 to 1f
                    5 -> 1 to 1f
                    else -> 2 to 1f
                }
                drawRect(
                    visTier(p, tier).copy(alpha = alpha),
                    Offset(c * unit * 3f, r * tileH),
                    Size(tileW, tileH),
                )
            }
        }
    }
}
