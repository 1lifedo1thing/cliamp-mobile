package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisBubbles(frame: BubblesFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val bands = VisMath.resampleAverage(frame.bands, frame.columns.coerceAtLeast(1))
        val energy = bands.average().toFloat()
        val f = frame.frame.toLong()
        for (i in 0 until BubblesCore.COUNT) {
            val bubble = BubblesCore.bubble(i, f, size.width, size.height, energy)
            if (bubble.alpha <= 0.02f) continue
            val at = Offset(
                bubble.x.coerceIn(0f, size.width),
                bubble.y.coerceIn(0f, size.height),
            )
            drawCircle(
                p.accent.copy(alpha = bubble.alpha.coerceIn(0f, 1f)),
                bubble.r.coerceAtLeast(0.5f),
                at,
                style = Stroke((bubble.r * 0.22f).coerceIn(0.5f, 2f)),
            )
            drawCircle(
                androidx.compose.ui.graphics.Color.White.copy(alpha = bubble.alpha * 0.8f),
                (bubble.r * 0.16f).coerceAtLeast(0.4f),
                at + Offset(-bubble.r * 0.3f, -bubble.r * 0.3f),
            )
        }
    }
}
