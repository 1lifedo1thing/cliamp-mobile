package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisScope(frame: ScopeFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val samples = frame.samples
        if (samples.size < 2 || size.width <= 0f || size.height <= 0f) return@Canvas
        val delay = ScopeCore.delayFor(frame.frame.toLong(), samples.size)
        val count = 256
        val path = Path()
        for (i in 0 until count) {
            val (sx, sy) = ScopeCore.point(samples, delay, i, count)
            val x = size.width * (0.5f + 0.48f * sx)
            val y = size.height * (0.5f - 0.48f * sy)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, p.accent.copy(alpha = 0.7f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
    }
}
