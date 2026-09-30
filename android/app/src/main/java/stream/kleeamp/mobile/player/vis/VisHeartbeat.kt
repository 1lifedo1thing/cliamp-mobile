package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * The ECG trace: ECG-shaped samples (squared magnitude sharpens QRS spikes,
 * flattens baseline noise) drawn as one continuous line over a faint dashed
 * center baseline - the hospital-monitor look. In cliamp this is braille
 * cells so small the dots merge into a line; at phone dot sizes discrete
 * braille reads as scattered dots instead, so the line is drawn directly
 * while shaping, colors and 60 Hz wall-clock data stay cliamp-faithful.
 *
 * Frame cost control: the three paths are remembered and reset (no per-frame
 * allocation after warmup) and the 1024-sample window is strided to at most
 * [MAX_POINTS] draw points - a screen cannot resolve more.
 */
private const val MAX_POINTS = 512

@Composable
internal fun VisHeartbeat(frame: HeartbeatFrame, modifier: Modifier) {
    val p = LocalPalette.current
    val dashes = remember { Path() }
    val trace = remember { Path() }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val samples = frame.samples
        if (samples.size < 2 || size.width <= 0f || size.height <= 0f) return@Canvas
        // Faint dashed baseline at center.
        dashes.reset()
        var x = 0f
        while (x < size.width) {
            dashes.moveTo(x, size.height / 2f)
            dashes.lineTo((x + 8f).coerceAtMost(size.width), size.height / 2f)
            x += 14f
        }
        drawPath(
            dashes,
            p.inkFaint.copy(alpha = 0.5f),
            style = Stroke(1f),
        )
        // One path built once, drawn twice: crisp trace plus glow.
        trace.reset()
        val stride = ((samples.size - 1) / MAX_POINTS).coerceAtLeast(1)
        var drawn = 0
        var i = 0
        while (i < samples.size) {
            val px = size.width * i / (samples.size - 1)
            val py = size.height * HeartbeatCore.yFrac(HeartbeatCore.shaped(samples[i]))
            if (drawn == 0) trace.moveTo(px, py) else trace.lineTo(px, py)
            drawn++
            i += stride
        }
        drawPath(
            trace,
            p.accentBright,
            style = Stroke(2.2f),
        )
        // Trace glow underneath.
        drawPath(
            trace,
            p.accent.copy(alpha = 0.3f),
            style = Stroke(5f),
        )
    }
}
