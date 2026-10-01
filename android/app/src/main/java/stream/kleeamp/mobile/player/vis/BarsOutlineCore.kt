package stream.kleeamp.mobile.player.vis

import kotlin.math.ceil

/**
 * Bar peak rows, mirroring cliamp's renderBarsOutline row logic: the single
 * row with `rowTop > level > rowBottom` draws, everything else stays empty.
 * Full scale draws nothing (every row's top sits at or below the peak) and
 * silence draws nothing - both exactly like the terminal original.
 */
object BarsOutlineCore {

    /** Row containing [level]'s peak in a [rows]-tall field, or null. */
    fun peakRow(level: Float, rows: Int): Int? {
        if (rows <= 0 || level <= 0f || level >= 1f) return null
        return (ceil(rows * (1.0 - level)) - 1).toInt().coerceIn(0, rows - 1)
    }
}
