package stream.kleeamp.mobile.player.vis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Pulsating radial ellipse, mirroring cliamp's renderPulse: the radius at
 * each angle blends per-band energy with the overall level so the shape
 * surges on beats, breathes in silence, rotates slowly, and throws a
 * shockwave ring on transients.
 */
object PulseCore {

    fun average(bands: FloatArray): Float {
        if (bands.isEmpty()) return 0f
        var sum = 0f
        for (b in bands) sum += b.coerceIn(0f, 1f)
        return sum / bands.size
    }

    /**
     * Radius fraction (0..~1.2) at [angleNorm] (0..1 around the dial) for
     * [frame], mirroring the Go blend: cosine-interpolated band energy at
     * 60%, overall average at 40%, squared punch, breathing offsets.
     */
    fun radius(
        angleNorm: Double,
        bands: FloatArray,
        avg: Float,
        frame: Long,
    ): Double {
        if (bands.isEmpty()) return 0.0
        val energy = bandEnergy(angleNorm, bands)
        val blended = energy * 0.6 + avg * 0.4
        val punch = blended * blended
        val breath = sin(frame * 0.05) * 0.02
        return 0.08 + breath + 0.92 * punch
    }

    /** Rotation offset added to every angle, in dial turns. */
    fun rotation(frame: Long, avg: Float): Double =
        (frame * (0.015 + avg * 0.04)) % 1.0

    /**
     * Shockwave ring as (radius fraction, strength), or null when calm.
     * Mirrors the Go expanding ring that fades as it grows.
     */
    fun shock(frame: Long, avg: Float): Pair<Double, Double>? {
        val phase = (frame * 0.10) % 1.0
        val strength = avg * avg * (1.0 - phase * phase)
        if (strength <= 0.05) return null
        return Pair(0.3 + 0.7 * phase, strength)
    }

    /** Wobble-free helper for tests: band interpolation at an angle. */
    fun bandEnergy(angleNorm: Double, bands: FloatArray): Double {
        if (bands.isEmpty()) return 0.0
        val bandPos = angleNorm * bands.size
        val lo = floor(bandPos).toInt()
        val frac = bandPos - floor(bandPos)
        val t = (1 - cos(frac * PI)) / 2
        val n = bands.size
        return bands[((lo % n) + n) % n] * (1 - t) + bands[(((lo + 1) % n) + n) % n] * t
    }
}
