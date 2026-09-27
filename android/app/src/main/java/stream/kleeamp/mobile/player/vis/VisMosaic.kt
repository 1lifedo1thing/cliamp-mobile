package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisMosaic(frame: MosaicFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val core = frame.core
        if (core.rows == 0 || core.cols == 0) return@Canvas
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val cellW = size.width / core.cols
        val cellH = size.height / core.rows
        val tileW = cellW * 0.78f
        val tileH = cellH * 0.78f
        for (r in 0 until core.rows) {
            for (c in 0 until core.cols) {
                val v = core.value[r * core.cols + c]
                if (v <= 0.02f) continue
                drawRoundRect(
                    visTier(p, VisMath.tier(v)).copy(alpha = (0.25f + 0.75f * v)),
                    topLeft = Offset(
                        c * cellW + (cellW - tileW) / 2f,
                        r * cellH + (cellH - tileH) / 2f,
                    ),
                    size = Size(tileW.coerceAtLeast(0.5f), tileH.coerceAtLeast(0.5f)),
                    cornerRadius = CornerRadius(tileH * 0.18f, tileH * 0.18f),
                )
            }
        }
    }
}
