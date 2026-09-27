package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisRain(frame: RainFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val cols = frame.columns
        if (cols == 0 || size.height <= 0f) return@Canvas
        val rows = 24
        val cellW = size.width / cols
        val cellH = size.height / rows
        val bands = VisMath.resampleAverage(frame.bands, cols)
        for (col in 0 until cols) {
            val level = bands[col].coerceIn(0f, 1f)
            if (level > 0.02f &&
                RainCore.active(col, col, frame.frame.toLong(), level)
            ) {
                drawDrop(col, frame.frame.toLong(), DropCtx(rows, level, cellW * (col + 0.5f), cellW, cellH, p.accent))
            }
        }
    }
}

/** Shared lane geometry for one column's drop. */
private data class DropCtx(
    val rows: Int,
    val level: Float,
    val x: Float,
    val cellW: Float,
    val cellH: Float,
    val ink: Color,
)

/** One column's falling drop: bright head fading into the tail. */
private fun DrawScope.drawDrop(col: Int, frame: Long, ctx: DropCtx) {
    val drop = RainCore.drop(col, ctx.rows)
    val head = RainCore.headPos(frame, drop)
    for (d in 0 until drop.len) {
        drawDropCell(head - d, d, ctx)
    }
}

/** One drop segment, drawn only inside the lane and below the bar top. */
private fun DrawScope.drawDropCell(row: Int, d: Int, ctx: DropCtx) {
    if (row in 0 until ctx.rows && (ctx.rows - 1 - row).toFloat() / ctx.rows <= ctx.level) {
        val alpha = when (d) {
            0 -> 1f
            1 -> 0.65f
            else -> 0.4f
        }
        drawLine(
            ctx.ink.copy(alpha = alpha),
            Offset(ctx.x, row * ctx.cellH),
            Offset(ctx.x, (row + 1) * ctx.cellH),
            strokeWidth = (ctx.cellW * 0.42f).coerceAtLeast(1f),
        )
    }
}
