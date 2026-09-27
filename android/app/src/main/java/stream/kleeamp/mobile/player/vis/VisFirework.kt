package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisFirework(frame: FireworkFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val bands = VisMath.resampleAverage(frame.bands, frame.columns.coerceAtLeast(1))
        val energy = bands.average().toFloat()
        val f = frame.frame.toLong()
        val count = FireworkCore.burstCount(energy)
        for (b in 0 until count) {
            for (spark in FireworkCore.burst(b, f, size.width, size.height, energy)) {
                if (spark.alpha <= 0.02f) continue
                drawCircle(
                    p.accentBright.copy(alpha = spark.alpha.coerceIn(0f, 1f)),
                    (size.minDimension * 0.008f + 1f).coerceIn(1f, 4f),
                    Offset(
                        spark.x.coerceIn(0f, size.width),
                        spark.y.coerceIn(0f, size.height),
                    ),
                )
            }
        }
    }
}
