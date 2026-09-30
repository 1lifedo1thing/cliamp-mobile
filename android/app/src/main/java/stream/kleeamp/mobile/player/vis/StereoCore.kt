package stream.kleeamp.mobile.player.vis

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class StereoMetrics(
    val leftLevel: Float,
    val rightLevel: Float,
    val leftPeak: Float,
    val rightPeak: Float,
) {
    companion object {
        val silent = StereoMetrics(0f, 0f, 0f, 0f)
    }
}

class StereoCore {

    val levels = FloatArray(2)
    val peaks = FloatArray(2)
    private val hold = FloatArray(2)

    fun push(target: StereoMetrics, dt: Float) {
        val step = clampDt(dt)
        stepChannel(0, target.leftLevel, target.leftPeak, step)
        stepChannel(1, target.rightLevel, target.rightPeak, step)
    }

    /** Rest state for pause: stubs at the meter floor, no hold. */
    fun settle() {
        levels.fill(REST_LEVEL)
        peaks.fill(REST_PEAK)
        hold.fill(0f)
    }

    /**
     * No-signal input: ease toward true silence. Never synthesizes motion —
     * a stereo meter with no PCM rests, it does not dance.
     */
    fun silence(dt: Float) = push(StereoMetrics.silent, dt)

    private fun stepChannel(channel: Int, targetLevel: Float, targetPeak: Float, dt: Float) {
        val rate = if (targetLevel > levels[channel]) RISE_RATE else FALL_RATE
        levels[channel] += (targetLevel - levels[channel]) * (1f - kotlin.math.exp(-rate * dt))

        when {
            targetPeak > peaks[channel] -> {
                peaks[channel] = targetPeak
                hold[channel] = PEAK_HOLD
            }
            hold[channel] > 0f -> hold[channel] = max(0f, hold[channel] - dt)
            else -> peaks[channel] = max(levels[channel], peaks[channel] - PEAK_FALL * dt)
        }
    }

    private fun clampDt(dt: Float): Float = when {
        dt <= 0f || dt > 10f * FRAME -> FRAME
        else -> dt
    }

    companion object {
        private const val FRAME = 1f / 60f
        private const val RISE_RATE = 36f
        private const val FALL_RATE = 10f
        private const val PEAK_HOLD = 0.45f
        private const val PEAK_FALL = 0.65f
        private const val FLOOR_DB = -48.0
        /** Matches MeterCore.settle so every family rests on the same floor. */
        private const val REST_LEVEL = 0.04f
        private const val REST_PEAK = 0.06f

        fun dbLevel(amplitude: Double): Float {
            if (amplitude <= 0.0) return 0f
            val db = 20 * log10(amplitude)
            return max(0.0, min(1.0, (db - FLOOR_DB) / -FLOOR_DB)).toFloat()
        }

        fun metrics(
            sumSquaresLeft: Double,
            peakLeft: Double,
            sumSquaresRight: Double,
            peakRight: Double,
            frames: Int,
        ): StereoMetrics {
            if (frames <= 0) return StereoMetrics.silent
            return StereoMetrics(
                leftLevel = dbLevel(sqrt(sumSquaresLeft / frames)),
                rightLevel = dbLevel(sqrt(sumSquaresRight / frames)),
                leftPeak = dbLevel(peakLeft),
                rightPeak = dbLevel(peakRight),
            )
        }

        fun peakOf(samples: FloatArray): Double {
            var peak = 0.0
            for (s in samples) {
                val a = abs(s.toDouble())
                if (a > peak) peak = a
            }
            return peak
        }
    }
}
