package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisPulse(frame: PulseFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val bands = VisMath.resampleAverage(frame.bands, frame.columns.coerceAtLeast(1))
        if (bands.isEmpty() || size.minDimension <= 0f) return@Canvas
        val avg = PulseCore.average(bands)
        val f = frame.frame.toLong()
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxR = size.minDimension / 2f * 0.92f
        val rot = PulseCore.rotation(f, avg)
        val samples = 72
        val path = Path().apply {
            for (i in 0..samples) {
                val a = (i % samples).toDouble() / samples + rot
                val r = (maxR * PulseCore.radius(a % 1.0, bands, avg, f)).toFloat()
                val x = center.x + (r * cos(a * 2 * PI)).toFloat()
                val y = center.y + (r * sin(a * 2 * PI)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(path, p.accent.copy(alpha = 0.45f))
        drawPath(
            path,
            p.accentBright,
            style = androidx.compose.ui.graphics.drawscope.Stroke(maxR * 0.035f + 1f),
        )
        PulseCore.shock(f, avg)?.let { (shockR, strength) ->
            drawCircle(
                p.accent.copy(alpha = (0.55 * strength).toFloat().coerceIn(0f, 0.6f)),
                (maxR * shockR).toFloat(),
                center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    (maxR * 0.03f + 1f).coerceAtLeast(1f),
                ),
            )
        }
    }
}
