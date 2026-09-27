package stream.kleeamp.mobile.player.vis

import kotlin.math.roundToInt

/**
 * Stippled bars: each column is a dot grid filled bottom-up by the band
 * level, mirroring cliamp's braille-dot bars on a Canvas.
 */
object BarsDotCore {

    /** Lit dots of a [dots]-tall column at [level]. */
    fun filled(dots: Int, level: Float): Int =
        (level.coerceIn(0f, 1f) * dots).roundToInt().coerceIn(0, dots)
}
