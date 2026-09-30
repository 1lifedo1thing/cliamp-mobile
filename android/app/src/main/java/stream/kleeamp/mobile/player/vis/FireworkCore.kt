package stream.kleeamp.mobile.player.vis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Firework bursts, ported from cliamp's renderFirework
 * (`ui/vis_firework.go`): each burst rises from the bottom with a short
 * trail, then explodes into jittered particles that drift down with gravity
 * and stochastically fade. Energy drives burst count (5 quiet, up to 14
 * loud) and explosion size. Closed-form in frame: 48-frame cycles offset
 * per burst, fully deterministic.
 */
object FireworkCore {

    const val CYCLE = 48L
    const val LAUNCH = 10L

    /** Simultaneous burst count: 5 quiet, up to 14 loud. */
    fun burstCount(avgEnergy: Float): Int =
        (5 + avgEnergy.coerceIn(0f, 1f) * 9).toInt().coerceIn(5, 14)

    /**
     * Stamped dots of burst [i] at [frame] on a [dotCols]x[dotRows] grid,
     * each as (x, y). Callers clip out-of-grid dots, like cliamp.
     *
     * @param bands spectrum bands for per-burst energy sizing.
     */
    fun dots(
        i: Int,
        frame: Long,
        dotCols: Int,
        dotRows: Int,
        bands: FloatArray,
    ): List<Pair<Int, Int>> {
        if (dotCols < 4 || dotRows < 4) return emptyList()
        // Seed changes each cycle so bursts appear in new positions.
        val cycle = (frame + i * 7) / CYCLE
        val seed = cycle * 104729L + i * 7919L
        // Stagger starts so bursts don't all fire simultaneously.
        val numBursts = burstCount(average(bands))
        val offset = (i * CYCLE / numBursts + (seed / 3) % 5)
        val local = (frame + offset) % CYCLE
        // Burst center spread across the panel, upper portion.
        val cx = VisMath.spreadSeed(seed * 6271L, dotCols)
        val cy = VisMath.spreadSeed(seed * 4391L, dotRows / 2) + dotRows / 8
        val bandIdx = if (bands.isEmpty()) 0 else ((seed % bands.size).toInt())
        val energy = if (bands.isEmpty()) 0f else bands[bandIdx].coerceIn(0f, 1f)

        val out = ArrayList<Pair<Int, Int>>()
        if (local < LAUNCH) {
            // Rising trail from bottom to burst center.
            val progress = local.toDouble() / LAUNCH
            val trailY = dotRows - 1 - ((dotRows - 1 - cy) * progress).toInt()
            for (dy in 0 until 4) {
                out += cx to trailY + dy
            }
            return out
        }
        // Burst expansion and fade.
        val burstT = (local - LAUNCH).toDouble() / (CYCLE - LAUNCH)
        val maxRadius = 3.0 + energy * 8.0
        // Fast expansion, then slow drift.
        val radius = maxRadius * min(burstT * 3.0, 1.0)
        // Gravity pulls particles down over time.
        val gravity = burstT * burstT * 5.0
        // Particles fade out over time.
        val fade = maxOf(0.0, 1.0 - burstT * 1.3)
        val numParticles = 18 + (energy * 18).toInt()
        for (p in 0 until numParticles) {
            val angle = p.toDouble() / numParticles * 2 * PI
            val pSeed = seed + p * 2909L
            val speed = 0.6 + (pSeed % 400).toDouble() / 1000.0
            val px = cx + (cos(angle) * radius * speed).toInt()
            val py = cy + (sin(angle) * radius * speed + gravity).toInt()
            // Stochastic fade - more particles disappear as time passes.
            if (VisMath.scatterHash(bandIdx, p, (seed % 100).toInt(), frame).toDouble() > fade) continue
            out += px to py
        }
        return out
    }

    private fun average(bands: FloatArray): Float {
        if (bands.isEmpty()) return 0f
        var sum = 0f
        for (b in bands) sum += b
        return sum / bands.size
    }
}
