package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisSakura(frame: SakuraFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val bands = VisMath.resampleAverage(frame.bands, frame.columns.coerceAtLeast(1))
        val energy = bands.average().toFloat()
        val f = frame.frame.toLong()
        for (i in 0 until SakuraCore.PETALS) {
            val petal = SakuraCore.petal(i, f, size.width, size.height, energy)
            if (petal.r <= 0f) continue
            rotate(i * 37f % 360f, Offset(petal.x, petal.y)) {
                drawOval(
                    p.accentBright.copy(alpha = 0.85f),
                    topLeft = Offset(petal.x - petal.r, petal.y - petal.r * 0.62f),
                    size = androidx.compose.ui.geometry.Size(petal.r * 2f, petal.r * 1.24f),
                )
            }
            drawCircle(
                androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                petal.r * 0.28f,
                Offset(petal.x, petal.y),
            )
        }
    }
}
