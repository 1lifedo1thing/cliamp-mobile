package stream.kleeamp.mobile.player.vis

import kotlin.math.abs

/**
 * ECG trace shaping, mirroring cliamp's renderHeartbeat: squaring the
 * magnitude sharpens QRS spikes and flattens noise around the baseline.
 */
object HeartbeatCore {

    /** Sharpened sample: squared magnitude, sign kept. */
    fun shaped(sample: Float): Float = sample * abs(sample)

    /** Y fraction (0 top, 1 bottom) for a shaped sample. */
    fun yFrac(shaped: Float): Float = (0.5f - shaped * 0.45f).coerceIn(0f, 1f)
}
