package stream.kleeamp.mobile.player.vis

/**
 * Stippled-bar dot threshold, mirroring cliamp's renderBarsDot: dots fill
 * bottom-up, so a dot lights when its height fraction sits below the level.
 */
object BarsDotCore {

    /** Whether the dot at [dotRow] (0 = top) of [dotRows] lights at [level]. */
    fun dotLit(dotRows: Int, dotRow: Int, level: Float): Boolean {
        if (dotRows <= 0) return false
        val dotY = (dotRows - 1 - dotRow).toFloat() / dotRows
        return dotY < level.coerceIn(0f, 1f)
    }
}
