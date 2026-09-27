package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisHeartbeat(frame: HeartbeatFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val samples = frame.samples
        if (samples.size < 2 || size.width <= 0f || size.height <= 0f) return@Canvas
        // Faint dashed baseline at center.
        val dashes = Path().apply {
            var x = 0f
            while (x < size.width) {
                moveTo(x, size.height / 2f)
                lineTo((x + 8f).coerceAtMost(size.width), size.height / 2f)
                x += 14f
            }
        }
        drawPath(
            dashes,
            p.inkFaint.copy(alpha = 0.5f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1f),
        )
        val path = Path()
        val n = samples.size
        for (i in 0 until n) {
            val x = size.width * i / (n - 1)
            val y = size.height * HeartbeatCore.yFrac(HeartbeatCore.shaped(samples[i]))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            p.accentBright,
            style = androidx.compose.ui.graphics.drawscope.Stroke(2.2f),
        )
        // Trace glow underneath.
        drawPath(
            path,
            p.accent.copy(alpha = 0.3f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(5f),
        )
    }
}
