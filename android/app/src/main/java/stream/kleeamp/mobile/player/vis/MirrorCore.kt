package stream.kleeamp.mobile.player.vis

import kotlin.math.abs
import kotlin.math.sin

/**
 * Mirrored spectrum bars around a persistent horizontal axis, mirroring
 * cliamp's renderMirror: bar height tapers with distance from the center
 * and wobbles on two slow sines so the field breathes.
 */
object MirrorCore {

    fun average(bands: FloatArray): Float {
        if (bands.isEmpty()) return 0f
        var sum = 0f
        for (b in bands) sum += b.coerceIn(0f, 1f)
        return sum / bands.size
    }

    /**
     * Bar radius as a fraction of the half-height for bar [i] of
     * [barCount] at time [t] seconds, mirroring the Go amplitude product
     * of taper, envelope and wobble.
     */
    fun radiusFrac(i: Int, barCount: Int, env: Float, t: Double): Float {
        if (barCount <= 0) return 0f
        val half = (barCount - 1) / 2.0
        val distance = if (half > 0) abs(i - half) / half else 0.0
        val wobble = 0.4 + 0.6 * abs(sin(t * 4.6 + i * 0.42) * sin(t * 1.9 - i * 0.13))
        return (0.80 * (1 - distance * 0.55) * (0.3 + 0.7 * env) * (0.35 + 0.65 * wobble)).toFloat()
    }
}
