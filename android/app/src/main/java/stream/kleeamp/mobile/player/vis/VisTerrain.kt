package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisTerrain(frame: TerrainFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val heights = frame.core.heights()
        if (heights.isEmpty()) return@Canvas
        val n = heights.size
        val silhouette = Path().apply {
            moveTo(0f, size.height)
            for (i in heights.indices) {
                lineTo(size.width * i / (n - 1).coerceAtLeast(1), size.height * (1f - heights[i]))
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(silhouette, p.accent.copy(alpha = 0.45f))
        // Ridge highlight: the top edge walks the same heights brighter.
        val ridge = Path().apply {
            for (i in heights.indices) {
                val x = size.width * i / (n - 1).coerceAtLeast(1)
                val y = size.height * (1f - heights[i])
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(
            ridge,
            p.accentBright,
            style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
        )
    }
}
