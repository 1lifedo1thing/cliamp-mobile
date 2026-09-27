package stream.kleeamp.mobile.player.vis

/**
 * Lissajous XY oscilloscope, mirroring cliamp's renderScope: the mono tap
 * feeds X, a phase-delayed copy feeds Y, and the delay wobbles slowly so
 * pure tones draw evolving circles and music draws knots.
 */
object ScopeCore {

    /** Phase delay in samples, wobbling with [frame]. */
    fun delayFor(frame: Long, n: Int): Int {
        if (n <= 1) return 0
        val base = n / 4
        val wobble = (kotlin.math.sin(frame * 0.02) * (n / 8)).toInt()
        return (base + wobble).coerceIn(1, n - 1)
    }

    /** XY pair [i] of [count] across the delay line. */
    fun point(samples: FloatArray, delay: Int, i: Int, count: Int): Pair<Float, Float> {
        if (samples.size < 2 || count <= 0) return 0f to 0f
        val n = samples.size
        val idx = (i.toLong() * (n - delay) / count).toInt().coerceIn(0, n - delay - 1)
        return samples[idx] to samples[idx + delay]
    }
}
