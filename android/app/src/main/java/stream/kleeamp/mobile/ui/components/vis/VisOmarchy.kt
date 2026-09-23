package stream.kleeamp.mobile.ui.components.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import stream.kleeamp.mobile.data.visualizer.OmarchyField
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisOmarchy(frame: OmarchyFrame, modifier: Modifier) {
    val p = LocalPalette.current
    val field = remember { OmarchyField() }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (frame.bands.isEmpty() || size.width <= 0f || size.height <= 0f) return@Canvas
        val pitch = size.width / PX_COLS
        val pxCols = (size.width / pitch).toInt()
        val pxRows = (size.height / pitch).toInt()
        if (pxCols <= 0 || pxRows <= 0) return@Canvas
        val sampler = field.sampler(frame.bands, frame.frame.toLong(), pxRows, pxCols)
        val gap = pitch * 0.14f
        val cell = Size(pitch - gap, pitch - gap)
        for (pr in 0 until pxRows) {
            for (pc in 0 until pxCols) {
                val pixel = sampler.pixel(pr, pc)
                if (pixel.lit) {
                    drawRect(visTier(p, pixel.tier), Offset(pc * pitch, pr * pitch), cell)
                }
            }
        }
    }
}

private const val PX_COLS = 180f
