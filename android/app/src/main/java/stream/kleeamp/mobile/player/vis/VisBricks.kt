package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisBricks(frame: BricksFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val cols = frame.columns
        if (cols == 0 || size.height <= 0f) return@Canvas
        val bands = VisMath.resampleAverage(frame.bands, cols)
        val rows = 10
        val cellW = size.width / cols
        val brickW = cellW * 0.68f
        val brickH = size.height / rows * 0.70f
        for (col in 0 until cols) {
            val lit = BricksCore.litRows(bands[col], rows)
            for (r in 0 until lit) {
                val norm = (r + 1f) / rows
                drawRoundRect(
                    visTier(p, VisMath.tier(norm)),
                    topLeft = Offset(
                        col * cellW + (cellW - brickW) / 2f,
                        size.height - (r + 1f) * size.height / rows +
                            (size.height / rows - brickH) / 2f,
                    ),
                    size = Size(brickW.coerceAtLeast(0.5f), brickH.coerceAtLeast(0.5f)),
                    cornerRadius = CornerRadius(brickH * 0.18f, brickH * 0.18f),
                )
            }
        }
    }
}
