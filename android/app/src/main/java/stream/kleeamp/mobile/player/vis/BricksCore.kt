package stream.kleeamp.mobile.player.vis

/**
 * Segmented brick bars: each column stacks brick rows with gaps, mirroring
 * cliamp's half-block bricks on Canvas rounded rects.
 */
object BricksCore {

    /** Lit brick rows of a [rows]-tall column at [level]. */
    fun litRows(level: Float, rows: Int): Int =
        (level.coerceIn(0f, 1f) * rows).toInt().coerceIn(0, rows)
}
