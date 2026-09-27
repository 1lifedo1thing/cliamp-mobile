package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisMirror(frame: MirrorFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val bars = frame.columns.coerceAtLeast(1)
        if (size.height <= 0f) return@Canvas
        val bands = VisMath.resampleAverage(frame.bands, bars)
        val env = MirrorCore.average(bands)
        val t = frame.frame * 1.0 / 60.0
        val axisY = size.height / 2f
        drawLine(
            p.inkFaint.copy(alpha = 0.6f),
            Offset(0f, axisY),
            Offset(size.width, axisY),
            strokeWidth = 1f,
        )
        val cellW = size.width / bars
        val halfH = size.height / 2f
        for (i in bands.indices) {
            val r = MirrorCore.radiusFrac(i, bars, env, t) * halfH
            if (r < 1f) continue
            val x = cellW * (i + 0.5f)
            drawLine(
                visTier(p, VisMath.tier(bands[i])),
                Offset(x, axisY - r),
                Offset(x, axisY + r),
                strokeWidth = (cellW * 0.52f).coerceIn(1f, 12f),
            )
        }
    }
}
