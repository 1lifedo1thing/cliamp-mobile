package stream.kleeamp.mobile.ui.components.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.ui.theme.LocalPalette

@Composable
internal fun VisClassicPeak(frame: ClassicPeakFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val n = frame.columns
        if (n == 0) return@Canvas
        val slot = size.width / n
        val barW = maxOf(1.dp.toPx(), slot * 0.55f)
        val capH = maxOf(1.dp.toPx(), 1.5.dp.toPx())
        for (c in 0 until n) {
            val bar = frame.bars[c].coerceIn(0f, 1f)
            val peak = frame.peaks[c].coerceIn(0f, 1f)
            val x = c * slot
            if (bar > 0f) {
                drawRect(
                    color = p.accent,
                    topLeft = Offset(x, size.height - bar * size.height),
                    size = Size(barW, bar * size.height),
                )
            }
            if (peak > bar + 0.01f) {
                val capY = (size.height - peak * size.height).coerceIn(0f, size.height - capH)
                drawRect(
                    color = p.peak,
                    topLeft = Offset(x, capY),
                    size = Size(barW, capH),
                )
            }
        }
    }
}
