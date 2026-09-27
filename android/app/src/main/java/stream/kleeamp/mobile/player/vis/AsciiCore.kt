package stream.kleeamp.mobile.player.vis

/**
 * Shade-block columns, mirroring cliamp's ascii renderer: thin columns
 * with 4-step fractional caps (full, 3/4, 1/2, 1/4).
 */
object AsciiCore {

    /** Shade steps of the cap: 4 full, 0 empty. */
    fun shade(level: Float, rowBottom: Float, rowTop: Float): Int {
        if (level >= rowTop) return 4
        if (level <= rowBottom) return 0
        val frac = (level - rowBottom) / (rowTop - rowBottom)
        return when {
            frac >= 0.75f -> 3
            frac >= 0.50f -> 2
            frac >= 0.25f -> 1
            else -> 0
        }
    }
}
