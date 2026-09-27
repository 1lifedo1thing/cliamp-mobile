package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisBarsOutline(frame: BarsOutlineFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val cols = frame.columns
        if (cols == 0 || size.height <= 0f) return@Canvas
        val bands = VisMath.resampleAverage(frame.bands, cols)
        val pts = BarsOutlineCore.tops(bands, size.width, size.height)
        if (pts.isEmpty()) return@Canvas
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        }
        drawPath(
            path,
            p.accent,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                (size.height / 48f).coerceIn(1f, 4f),
            ),
        )
        for (pt in pts) {
            drawCircle(p.accentBright, (size.height / 90f).coerceIn(0.5f, 2.5f), pt)
        }
    }
}
