package stream.kleeamp.mobile.ui.components.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.data.visualizer.ButterflyCore
import stream.kleeamp.mobile.data.visualizer.VisMath
import stream.kleeamp.mobile.ui.theme.LocalPalette

@Composable
internal fun VisButterfly(frame: ButterflyFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val pitch = 2.dp.toPx()
        val dotCols = (size.width / pitch).toInt()
        val dotRows = (size.height / pitch).toInt()
        if (dotCols <= 0 || dotRows <= 0) return@Canvas
        val grid = ButterflyCore.grid(frame.bands, dotRows, dotCols, frame.frame.toLong())
        val radius = pitch * 0.34f
        for (dy in 0 until dotRows) {
            val tier = VisMath.tier(1f - dy.toFloat() / maxOf(1, dotRows - 1))
            val color = visTier(p, tier)
            for (dx in 0 until dotCols) {
                if (grid[dy * dotCols + dx]) {
                    drawCircle(color, radius, Offset((dx + 0.5f) * pitch, (dy + 0.5f) * pitch))
                }
            }
        }
    }
}
