package stream.kleeamp.mobile.player.vis

import kotlin.math.sin

/**
 * Rising air bubbles, mirroring cliamp's renderBubbles: a fixed population
 * of hollow rings drifting upward with lateral sway, fading near the
 * surface so they appear to pop. Energy modulates sway and highlight.
 */
object BubblesCore {

    const val COUNT = 18

    data class Bubble(val x: Float, val y: Float, val r: Float, val alpha: Float)

    fun bubble(i: Int, frame: Long, width: Float, height: Float, energy: Float): Bubble {
        val seed = i.toLong() * 3347902833L + 7L
        val speed = 1 + (seed % 2)
        val laneH = height + 60f
        // Rise: 0 at the bottom, laneH at the surface.
        val rise = ((seed % 61) + frame * speed) % laneH.toLong()
        val y = height + 30f - rise
        val sway = sin(frame * 0.04 + i * 2.1) * (6 + energy * 10)
        val x = (seed * 17) % width.coerceAtLeast(1f).toLong() + sway.toLong()
        val r = 4 + (seed % 7)
        // Pop fade in the top eighth.
        val popZone = (height - y) / (height * 0.125f).coerceAtLeast(1f)
        val alpha = if (rise > laneH - height * 0.125f) {
            (1f - popZone).coerceIn(0f, 1f) * 0.9f
        } else {
            0.9f
        }
        return Bubble(x.toFloat(), y, r.toFloat(), alpha)
    }
}
