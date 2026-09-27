package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisRedSector(frame: RedSectorFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val angle = RedSectorCore.angle(frame.frame.toLong())
        // Starfield behind the bars.
        for (i in 0 until 40) {
            val (sx, sy, depth) = RedSectorCore.star(i, frame.frame.toLong())
            val (px, py) = RedSectorCore.project(sx, sy, size.width, size.height)
            drawCircle(
                p.inkFaint.copy(alpha = (0.25f + 0.45f * depth).toFloat()),
                (1f + depth * 1.6f).toFloat(),
                Offset(px, py),
            )
        }
        // Five hollow bars tumbling as a rigid body around Y.
        val heights = RedSectorCore.barHeights(frame.bands)
        val groundY = 0.72
        for (b in heights.indices) {
            val half = 0.11
            val cx = -0.8 + b * 0.4
            val top = groundY - heights[b] * 1.1
            // Front (z +) and back (z −) faces; weak perspective by depth.
            for ((z, dim) in listOf(0.15 to 1f, -0.15 to 0.45f)) {
                val quad = listOf(
                    RedSectorCore.rotY(cx - half, z, angle),
                    RedSectorCore.rotY(cx + half, z, angle),
                ).map { (rx, rz) ->
                    val scale = 1.0 / (1.0 + (rz + 0.5) * 0.35)
                    RedSectorCore.project(rx * scale, groundY, size.width, size.height) to
                        RedSectorCore.project(rx * scale, top * scale, size.width, size.height)
                }
                val ink = p.accentBright.copy(alpha = dim)
                for ((base, cap) in quad) {
                    drawLine(ink, Offset(base.first, base.second), Offset(cap.first, cap.second), 2f)
                }
                drawLine(
                    ink,
                    Offset(quad[0].second.first, quad[0].second.second),
                    Offset(quad[1].second.first, quad[1].second.second),
                    2f,
                )
            }
        }
    }
}
