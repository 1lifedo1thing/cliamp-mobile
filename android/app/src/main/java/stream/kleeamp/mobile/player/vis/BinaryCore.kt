package stream.kleeamp.mobile.player.vis

/**
 * Streaming 0/1 columns, mirroring cliamp's renderBinary: each column
 * scrolls at an energy-proportional speed, louder bands show more 1s.
 */
object BinaryCore {

    /** Bit for ([col], [row]) of a [rows]-tall field at [frame]. */
    fun bit(col: Int, row: Int, rows: Int, frame: Long, level: Float): Char {
        if (rows <= 0) return '0'
        val speed = maxOf(1, 4 - (level.coerceIn(0f, 1f) * 3).toInt())
        val lane = rows + 8
        val pos = ((frame / speed).toInt() + col * 5 + row) % lane
        val on = VisMath.scatterHash(col, row, pos, frame / speed) < level
        return if (on) '1' else '0'
    }
}
