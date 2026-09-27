package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisFlame(frame: FlameFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val core = frame.core
        if (core.cols == 0 || core.rows == 0) return@Canvas
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val cellW = size.width / core.cols
        val cellH = size.height / core.rows
        for (y in 0 until core.rows) {
            for (x in 0 until core.cols) {
                val tier = core.tierAt(x, y)
                if (tier < 0) continue
                // Hotter coals burn brighter near the source row.
                val glow = 1f - 0.35f * y / core.rows
                drawRect(
                    visTier(p, tier).copy(alpha = glow),
                    topLeft = Offset(x * cellW, y * cellH),
                    size = Size(cellW + 0.5f, cellH + 0.5f),
                )
            }
        }
    }
}
