package stream.kleeamp.mobile.player.vis

import kotlin.math.PI
import kotlin.math.sin

/**
 * Drifting cherry-blossom petals, mirroring cliamp's renderSakura: a fixed
 * population of petals with per-petal fall speed and lateral sway.
 * Energy sets how many are on screen - sparse in the quiet, full in the
 * loud. Closed-form in frame: no stored state, always deterministic.
 */
object SakuraCore {

    const val PETALS = 26

    data class Petal(val x: Float, val y: Float, val r: Float)

    /**
     * Petal [i] in a [width]x[height] field at [frame]; [energy] gates how
     * many of the population fly (the rest park offscreen).
     */
    fun petal(i: Int, frame: Long, width: Float, height: Float, energy: Float): Petal {
        val seed = i.toLong() * 2246822519L + 11L
        val speed = 1 + (seed % 3)
        val laneH = height + 40f
        val y = ((seed % 97) + frame * speed) % laneH.toLong() - 20L
        val sway = sin(frame * 0.03 + i * 1.7) * (8 + (seed % 5))
        val x = (seed * 31) % width.coerceAtLeast(1f).toLong() + sway.toLong()
        val r = 2 + (seed % 3)
        val flying = i < 6 + (energy * (PETALS - 6)).toInt().coerceIn(0, PETALS)
        return if (flying) Petal(x.toFloat(), y.toFloat(), r.toFloat()) else Petal(-100f, -100f, 0f)
    }
}
