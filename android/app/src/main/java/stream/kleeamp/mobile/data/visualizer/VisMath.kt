package stream.kleeamp.mobile.data.visualizer

import kotlin.math.PI
import kotlin.math.sin

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

    fun sampleLinear(bands: FloatArray, pos: Float): Float {
        if (bands.isEmpty()) return 0f
        if (bands.size == 1) return bands[0]
        if (pos <= 0f) return bands[0]
        val last = (bands.size - 1).toFloat()
        if (pos >= last) return bands[bands.size - 1]
        val idx = pos.toInt()
        val frac = pos - idx
        return bands[idx] * (1f - frac) + bands[idx + 1] * frac
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

    fun idleBands(columns: Int, t: Double): FloatArray {
        val out = FloatArray(columns)
        for (i in 0 until columns) {
            val period = 0.85 + (i % 7) * 0.11
            val phase = (i % 6) * 0.07
            val s = (sin(2 * PI * (t / period + phase)) + 1.0) / 2.0
            val bias = 0.34 + 0.5 * ((i * 37 % 13) / 13.0)
            out[i] = (0.12 + s * bias).coerceIn(0.0, 0.96).toFloat()
        }
        return out
    }

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
