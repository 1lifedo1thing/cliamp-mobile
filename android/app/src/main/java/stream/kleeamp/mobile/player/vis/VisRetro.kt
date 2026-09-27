package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.min
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisRetro(frame: RetroFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.height <= 0f || size.width <= 0f) return@Canvas
        val horizon = size.height * 0.40f
        val points = 48
        val wave = RetroCore.wavePoints(frame.bands, points)

        // Perspective grid floor.
        val vanishing = Offset(size.width / 2f, horizon)
        for (i in 0..18) {
            val bx = size.width * i / 18f
            drawLine(
                p.accentBevel.copy(alpha = 0.5f),
                vanishing,
                Offset(bx, size.height),
                strokeWidth = 1f,
            )
        }
        val scroll = RetroCore.scrollPhase(frame.frame.toLong())
        for (i in 0 until 10) {
            var z = (i + scroll) / 10.0
            if (z > 1.0) z -= 1.0
            val y = horizon + (size.height - horizon) * z * z
            drawLine(
                p.accentBevel.copy(alpha = (0.25f + 0.45f * z).toFloat()),
                Offset(0f, y.toFloat()),
                Offset(size.width, y.toFloat()),
                strokeWidth = 1f,
            )
        }
        drawLine(p.accent.copy(alpha = 0.8f), Offset(0f, horizon), Offset(size.width, horizon), 2f)

        // Striped setting sun.
        val sunR = min(size.width, horizon) * 0.42f
        val sunC = Offset(size.width / 2f, horizon - sunR * 0.25f)
        drawCircle(p.accent.copy(alpha = 0.85f), sunR, sunC)
        val stripes = 4
        for (s in 0 until stripes) {
            val y = horizon - sunR * 0.1f * s - 1f
            if (y < sunC.y) {
                drawRect(
                    p.ground,
                    topLeft = Offset(sunC.x - sunR, y - 1.5f),
                    size = androidx.compose.ui.geometry.Size(sunR * 2f, 3f),
                )
            }
        }

        // Audio-reactive wave above the horizon.
        val path = Path().apply {
            for (i in 0 until points) {
                val x = size.width * i / (points - 1)
                val y = horizon * (1f - wave[i] * 0.85f) - 2f
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(path, p.accentBright, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
        drawPath(path, p.accent.copy(alpha = 0.35f), style = androidx.compose.ui.graphics.drawscope.Stroke(6f))
    }
}
