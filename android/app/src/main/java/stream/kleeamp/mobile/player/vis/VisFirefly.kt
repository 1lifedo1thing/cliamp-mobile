package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisFirefly(frame: FireflyFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val bands = VisMath.resampleAverage(frame.bands, frame.columns.coerceAtLeast(1))
        val n = bands.size
        val bass = bands.take(n / 3).average().toFloat()
        val high = bands.drop(2 * n / 3).average().toFloat()
        val f = frame.frame.toLong()
        // Grass silhouette along the bottom.
        val grass = Path().apply {
            moveTo(0f, size.height)
            var x = 0f
            while (x <= size.width) {
                lineTo(x, size.height - FireflyCore.grassH(x, size.width))
                x += 4f
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(grass, p.inkFaint.copy(alpha = 0.35f))
        for (i in 0 until FireflyCore.FLIES) {
            val fly = FireflyCore.fly(i, f, size.width, size.height, bass, high) ?: continue
            val at = Offset(fly.x, fly.y)
            if (fly.lit) {
                drawCircle(p.accentBright.copy(alpha = 0.25f), 7f, at)
                drawCircle(p.accentBright, 2.6f, at)
            } else {
                drawCircle(p.inkFaint.copy(alpha = 0.4f), 1.4f, at)
            }
        }
    }
}
