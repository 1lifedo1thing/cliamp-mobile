package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisBars(frame: BarsFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val n = frame.columns
        if (n == 0) return@Canvas
        val gapPx = 2.dp.toPx()
        val colW = (size.width - gapPx * (n - 1)) / n
        if (colW <= 0f) return@Canvas
        for (c in 0 until n) {
            val level = frame.levels[c].coerceIn(0f, 1f)
            if (level <= 0f) continue
            val h = level * size.height
            drawRect(
                color = visTier(p, VisMath.tier(level)),
                topLeft = Offset(c * (colW + gapPx), size.height - h),
                size = Size(colW, h),
            )
        }
    }
}
