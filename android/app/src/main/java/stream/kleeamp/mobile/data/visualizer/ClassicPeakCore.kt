package stream.kleeamp.mobile.data.visualizer

import kotlin.math.exp

class ClassicPeakCore(val columns: Int) {

    val barPos = FloatArray(columns)
    val peakPos = FloatArray(columns)
    private val peakVel = FloatArray(columns)
    private val peakHold = FloatArray(columns)

    fun push(bands: FloatArray, dt: Float) {
        val step = clampDt(dt)
        val levels = VisMath.resampleAverage(bands, columns)
        if (barPos.size != levels.size) reset(levels)
        sync(levels)
        advance(levels, step)
    }

    /** Rest state for pause: stubs at the meter floor, no velocity or hold. */
    fun settle() {
        barPos.fill(REST_LEVEL)
        peakPos.fill(REST_PEAK)
        peakVel.fill(0f)
        peakHold.fill(0f)
    }

    private fun reset(levels: FloatArray) {
        levels.copyInto(barPos)
        levels.copyInto(peakPos)
        peakVel.fill(0f)
        peakHold.fill(0f)
    }

    private fun sync(levels: FloatArray) {
        for (i in levels.indices) {
            val landed = peakVel[i] == 0f && peakPos[i] <= barPos[i] + VISIBLE_EPSILON
            if (landed && levels[i] > peakPos[i]) {
                val delta = levels[i] - peakPos[i]
                peakPos[i] = levels[i]
                peakVel[i] = minOf(LAUNCH_MAX, LAUNCH_BASE + LAUNCH_GAIN * delta)
                peakHold[i] = 0f
            }
        }
    }

    private fun advance(levels: FloatArray, dt: Float) {
        for (i in levels.indices) {
            barPos[i] = step(barPos[i], levels[i], dt)

            if (peakHold[i] > 0f) {
                peakHold[i] = maxOf(0f, peakHold[i] - dt)
                if (peakHold[i] > 0f) continue
            }

            val prevVel = peakVel[i]
            peakPos[i] += peakVel[i] * dt
            peakVel[i] -= GRAVITY * dt

            if (peakPos[i] > MAX_HEIGHT) peakPos[i] = MAX_HEIGHT
            if (prevVel > 0f && peakVel[i] <= 0f && peakPos[i] > barPos[i] + VISIBLE_EPSILON) {
                peakVel[i] = 0f
                peakHold[i] = APEX_HOLD
                continue
            }
            if (peakPos[i] <= barPos[i]) {
                peakPos[i] = barPos[i]
                peakVel[i] = 0f
                peakHold[i] = 0f
            }
        }
    }

    private fun step(current: Float, target: Float, dt: Float): Float {
        val rate = if (target > current) BAR_RISE_RATE else BAR_FALL_RATE
        return current + (target - current) * (1f - exp(-rate * dt))
    }

    private fun clampDt(dt: Float): Float =
        if (dt <= 0f || dt > 10f * TICK) TICK else dt

    private companion object {
        const val TICK = 1f / 60f
        const val LAUNCH_BASE = 0.8f
        const val LAUNCH_GAIN = 1.4f
        const val LAUNCH_MAX = 1.7f
        const val GRAVITY = 9.5f
        const val APEX_HOLD = 0.08f
        const val BAR_RISE_RATE = 34f
        const val BAR_FALL_RATE = 10f
        const val MAX_HEIGHT = 1f
        const val VISIBLE_EPSILON = 0.01f
        /** Matches MeterCore.settle so every family rests on the same floor. */
        const val REST_LEVEL = 0.04f
        const val REST_PEAK = 0.06f
    }
}
