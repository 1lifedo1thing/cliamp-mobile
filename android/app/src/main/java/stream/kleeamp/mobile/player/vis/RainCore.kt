package stream.kleeamp.mobile.player.vis

/**
 * Falling-drop math behind the rain bars, mirroring cliamp's renderRain:
 * each column owns one drop with its own fall speed (1-3 rows per frame),
 * length (2-4 cells) and cycle offset. Density gates through the shared
 * scatter hash so louder bands rain harder.
 */
object RainCore {

    data class Drop(
        val speed: Int,
        val len: Int,
        val cycleLen: Int,
        val offset: Int,
    )

    fun drop(col: Int, rows: Int): Drop {
        val seed = col.toLong() * 7919L + 104729L
        val len = 2 + ((seed / 7) % 3).toInt()
        val cycleLen = rows + len + 3
        return Drop(
            speed = 1 + (seed % 3).toInt(),
            len = len,
            cycleLen = cycleLen,
            offset = ((seed / 13) % cycleLen).toInt(),
        )
    }

    /** Head row of the drop (0 = top), cycling through its lane. */
    fun headPos(frame: Long, drop: Drop): Int =
        ((frame / drop.speed).toInt() + drop.offset) % drop.cycleLen

    /** Column gate: closed columns stay empty no matter the level. */
    fun active(band: Int, col: Int, frame: Long, level: Float): Boolean =
        VisMath.scatterHash(band, 0, col, frame / 12) <= level * 1.6f + 0.1f
}
