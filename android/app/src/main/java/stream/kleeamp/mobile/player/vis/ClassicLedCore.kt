package stream.kleeamp.mobile.player.vis

import kotlin.math.exp

class ClassicLedCore(val columns: Int) {

    val body = FloatArray(columns)
    val peak = FloatArray(columns)
    private val hold = FloatArray(columns)

    fun push(bands: FloatArray, dt: Float) {
        val step = clampDt(dt)
        val levels = VisMath.resampleLinear(bands, columns)
        if (body.size != levels.size) {
            levels.copyInto(body)
            levels.copyInto(peak)
            hold.fill(0f)
            return
        }
        for (i in levels.indices) {
            val rate = if (levels[i] > body[i]) RISE_RATE else FALL_RATE
            body[i] += (levels[i] - body[i]) * (1f - exp(-rate * step))

            when {
                body[i] >= peak[i] -> {
                    peak[i] = body[i]
                    hold[i] = PEAK_HOLD
                }
                hold[i] > 0f -> hold[i] = maxOf(0f, hold[i] - step)
                else -> peak[i] = maxOf(body[i], peak[i] - PEAK_FALL * step)
            }
        }
    }

    /** Rest state for pause: stubs at the meter floor, no hold. */
    fun settle() {
        body.fill(REST_LEVEL)
        peak.fill(REST_PEAK)
        hold.fill(0f)
    }

    private fun clampDt(dt: Float): Float = when {
        dt <= 0f || dt > 10f * FRAME -> FRAME
        else -> dt
    }

    private companion object {
        const val FRAME = 1f / 30f
        const val RISE_RATE = 60f
        const val FALL_RATE = 16f
        const val PEAK_HOLD = 0.45f
        const val PEAK_FALL = 0.55f
        /** Matches MeterCore.settle so every family rests on the same floor. */
        const val REST_LEVEL = 0.04f
        const val REST_PEAK = 0.06f
    }
}
