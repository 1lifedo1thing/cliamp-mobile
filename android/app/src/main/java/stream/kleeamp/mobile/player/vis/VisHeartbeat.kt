package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import stream.kleeamp.mobile.theme.LocalPalette

/** Points per trace draw; see the stride note in [VisHeartbeat]. */
private const val MAX_POINTS = 512

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
        // Cap segments: the window holds 1024 samples but a phone screen
        // cannot resolve them, and 2k path segments per frame at 60 fps is
        // what made slow traces feel heavy. Striding keeps every pixel.
        val stride = ((samples.size - 1) / MAX_POINTS).coerceAtLeast(1)
        val path = Path()
        var drawn = 0
        var i = 0
        while (i < samples.size) {
            val x = size.width * i / (samples.size - 1)
            val y = size.height * HeartbeatCore.yFrac(HeartbeatCore.shaped(samples[i]))
            if (drawn == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawn++
            i += stride
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
