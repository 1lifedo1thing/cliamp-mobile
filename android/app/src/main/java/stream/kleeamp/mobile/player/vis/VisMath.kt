package stream.kleeamp.mobile.player.vis

object VisMath {

    fun scatterHash(band: Int, row: Int, col: Int, frame: Long): Float {
        val f = (frame + (row * 3 + col)) / 3
        var h = band.toULong() * 7919uL + row.toULong() * 6271uL +
            col.toULong() * 3037uL + f.toULong() * 104729uL
        h = h xor (h shr 16)
        h *= 0x45D9F3B37197344BuL
        h = h xor (h shr 16)
        return (h % 10000uL).toFloat() / 10000f
    }

    fun tier(norm: Float): Int = when {
        norm >= 0.6f -> 2
        norm >= 0.3f -> 1
        else -> 0
    }

    fun sampleLinear(bands: FloatArray, pos: Float): Float = when {
        bands.isEmpty() -> 0f
        bands.size == 1 || pos <= 0f -> bands[0]
        pos >= bands.size - 1 -> bands[bands.size - 1]
        else -> {
            val idx = pos.toInt()
            val frac = pos - idx
            bands[idx] * (1f - frac) + bands[idx + 1] * frac
        }
    }

    fun resampleLinear(bands: FloatArray, columns: Int): FloatArray {
        if (columns <= 0 || bands.isEmpty()) return FloatArray(columns.coerceAtLeast(0))
        if (bands.size == columns) return bands.copyOf()
        val out = FloatArray(columns)
        if (columns == 1) {
            out[0] = sampleLinear(bands, (bands.size - 1) / 2f)
            return out
        }
        val last = (bands.size - 1).toFloat()
        for (col in 0 until columns) {
            out[col] = sampleLinear(bands, col.toFloat() / (columns - 1) * last)
        }
        return out
    }

    fun resampleAverage(bands: FloatArray, columns: Int): FloatArray {
        if (columns <= 0) return FloatArray(0)
        if (bands.isEmpty()) return FloatArray(columns)
        if (columns >= bands.size) return resampleLinear(bands, columns)
        val out = FloatArray(columns)
        val span = bands.size.toFloat() / columns
        for (col in 0 until columns) {
            val lo = col * span
            val hi = (col + 1) * span
            var b = lo.toInt()
            while (b < bands.size && b.toFloat() < hi) {
                val weight = minOf(hi, (b + 1).toFloat()) - maxOf(lo, b.toFloat())
                out[col] += bands[b] * weight / span
                b++
            }
        }
        return out
    }

    /**
     * Silence frame: zeros of [columns]. Renderers use this while no PCM has
     * arrived yet so meters rest at the floor instead of dancing. Never a
     * sine wave, never random — silence in, silence out.
     */
    fun silenceBands(columns: Int): FloatArray = FloatArray(columns)

    /**
     * Spreads a seed stream uniformly onto `[0, n)`. A raw `seed % n` bunches
     * when the seed step is ≡ ±1 (mod n) for the grid at hand - cliamp's
     * petal step 104729 ≡ 1 (mod 106), parking every petal in adjacent
     * columns on a 106-wide grid. Reducing modulo a prime first keeps the
     * deterministic spread on every grid size.
     */
    fun spreadSeed(seed: Long, n: Int): Int {
        if (n <= 0) return 0
        return (((seed % PRIME) * n) / PRIME).toInt().coerceIn(0, n - 1)
    }

    private const val PRIME = 1009L

    /**
     * Per-capture smoothing, mirroring cliamp Analyze's fast-attack /
     * slow-decay blend (0.6 new on rises, 0.25 new on falls). The render-side
     * cores ease again per frame, but without this stage every FFT callback
     * would snap the shared levels and every consumer would step at the
     * capture rate instead of gliding. Resizes to the new input.
     */
    fun easeBands(prev: FloatArray, next: FloatArray): FloatArray {
        if (prev.size != next.size) return next.copyOf()
        for (i in next.indices) {
            val k = if (next[i] > prev[i]) RISE_BLEND else FALL_BLEND
            prev[i] += (next[i] - prev[i]) * k
        }
        return prev
    }

    private const val RISE_BLEND = 0.6f
    private const val FALL_BLEND = 0.25f
}
