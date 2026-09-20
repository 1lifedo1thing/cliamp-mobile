package stream.kleeamp.mobile.ui.components.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.data.visualizer.WaveCore
import stream.kleeamp.mobile.ui.theme.LocalPalette

/**
 * The oscilloscope, ported from cliamp's braille wave: raw time-domain
 * samples downsampled to one y per horizontal pixel and connected into a
 * continuous trace. Silence (and pause) is the flat center line, exactly
 * like the terminal original - a scope at rest draws its grid, not noise.
 */
@Composable
internal fun VisWave(frame: WaveFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        drawLine(
            color = p.hairline,
            start = Offset(0f, h / 2f),
            end = Offset(w, h / 2f),
            strokeWidth = 1.dp.toPx(),
        )
        val cols = w.toInt().coerceAtLeast(1)
        val trace = WaveCore.trace(frame.samples, cols)
        val path = Path()
        for (x in 0 until cols) {
            val px = x.toFloat() / maxOf(1, cols - 1) * w
            val py = trace[x] * h
            if (x == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = p.accent,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
