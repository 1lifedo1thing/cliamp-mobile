package stream.kleeamp.mobile.player.vis

import androidx.compose.ui.geometry.Offset

/**
 * Line-graph peak outline: one point per band top, mirroring cliamp's
 * bars-outline rows on a Canvas polyline.
 */
object BarsOutlineCore {

    /** Bar-top points across [width], y measured from the top. */
    fun tops(
        levels: FloatArray,
        width: Float,
        height: Float,
    ): List<Offset> {
        if (levels.isEmpty() || width <= 0f || height <= 0f) return emptyList()
        val cell = width / levels.size
        return levels.mapIndexed { i, level ->
            Offset(cell * (i + 0.5f), height * (1f - level.coerceIn(0f, 1f)))
        }
    }
}
