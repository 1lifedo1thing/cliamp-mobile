package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import stream.kleeamp.mobile.theme.KleeampPalette
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * Two koi circling a lily pad, ported from cliamp's yinYangDriver
 * (`ui/vis_yinyang.go`): kick/snare/hat onsets flick tails, swirls trail
 * flicks, the lotus breathes with the bass, strip panels run an S-curve.
 *
 * Like cliamp's Render, the dot grid is ensured from canvas dimensions
 * (cols*2 by rows*4, the same packing the terminal uses for its sextants).
 * Dots draw as rects instead of sextant glyphs so the phone needs no
 * sextant font — shapes, motion and tag colors match the TUI.
 */
@Composable
internal fun VisYinYang(frame: YinYangFrame, modifier: Modifier) {
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
        core.draw()

        val dotW = size.width / dotCols
        val dotH = size.height / dotRows
        if (dotW <= 0f || dotH <= 0f) return@Canvas
        for (y in 0 until core.dotRows) {
            val top = y * dotH
            for (x in 0 until core.dotCols) {
                val tag = core.tagAt(x, y)
                if (tag == 0) continue
                drawRect(
                    color = yinYangColor(p, tag),
                    topLeft = Offset(x * dotW, top),
                    size = Size(dotW + 0.5f, dotH + 0.5f),
                )
            }
        }
    }
}

/**
 * TUI tag palette from refreshYinYangANSI: dim swirls, pad low, red high,
 * ink text, gold mid.
 */
private fun yinYangColor(p: KleeampPalette, tag: Int): Color = when (tag) {
    YinYangCore.TAG_RED.toInt() -> visTier(p, 2)
    YinYangCore.TAG_GOLD.toInt() -> visTier(p, 1)
    YinYangCore.TAG_PAD.toInt() -> visTier(p, 0)
    YinYangCore.TAG_INK.toInt() -> p.ink
    else -> p.inkFaint.copy(alpha = 0.55f)
}
