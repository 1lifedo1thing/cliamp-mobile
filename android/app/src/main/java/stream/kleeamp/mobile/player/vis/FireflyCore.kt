package stream.kleeamp.mobile.player.vis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dusk fireflies over a grass silhouette, mirroring cliamp's renderFirefly:
 * each fly traces a slow per-seed Lissajous curve, blinks on a phase plus
 * high-band energy, and wears a halo when lit. Bass tilts a sideways wind.
 */
object FireflyCore {

    const val FLIES = 26

    data class Fly(val x: Float, val y: Float, val lit: Boolean)

    /** Ragged grass height in pixels at [x] of [width]. */
    fun grassH(x: Float, width: Float): Float {
        if (width <= 0f) return 0f
        val n = x / width * 40f
        return 4f + (2.5f + 1.5f * sin(n * 0.41) + 1.0f * sin(n * 0.17 + 2.3)).toFloat()
            .coerceAtLeast(1f)
    }

    fun fly(i: Int, frame: Long, width: Float, height: Float, bass: Float, high: Float): Fly? {
        if (width <= 0f || height <= 0f) return null
        val seed = i.toLong() * 2246822519L + 11L
        val fx = 0.012 + (seed % 17) / 3500.0
        val fy = 0.018 + ((seed shr 4) % 19) / 2900.0
        val phx = (seed % 1000) / 1000.0 * 2 * PI
        val phy = ((seed shr 8) % 1000) / 1000.0 * 2 * PI
        val t = frame.toDouble()
        val wind = bass * 1.5
        val x = (width / 2 + cos(t * fx + phx) * (width - 6) * 0.45 +
            wind * sin(t * 0.02 + phx)).toFloat()
        val y = (height * 0.5 + sin(t * fy + phy) * (height - 6) * 0.4).toFloat()
        if (x < 0f || x >= width || y < 0f || y >= height) return null
        if (height - y < grassH(x, width)) return null
        val on = sin(t * 0.18 + i * 1.31) * 0.5 + 0.5 + high * 0.4 > 0.55
        return Fly(x, y, on)
    }
}
