package stream.kleeamp.mobile.player.vis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Firework bursts, mirroring cliamp's renderFirework: each burst rises
 * from the bottom with a trail, then explodes into a sphere of particles
 * that fall with gravity and fade. Energy drives burst count and size.
 * Closed-form in frame: 48-frame cycles offset per burst.
 */
object FireworkCore {

    const val CYCLE = 48L
    const val SPOKES = 14

    data class Spark(val x: Float, val y: Float, val alpha: Float)

    /** Sparks of burst [b] in a [width]x[height] field at [frame]. */
    fun burst(
        b: Int,
        frame: Long,
        width: Float,
        height: Float,
        energy: Float,
    ): List<Spark> {
        val out = ArrayList<Spark>(SPOKES + 1)
        val phase = ((frame + b * 13) % CYCLE + CYCLE) % CYCLE
        val cx = width * (0.15f + 0.7f * ((b * 37 % 100) / 100f))
        val apex = height * (0.25f + 0.15f * ((b * 53 % 100) / 100f))
        val rise = 14L
        if (phase < rise) {
            // Rising trail.
            val t = phase.toFloat() / rise
            val y = height - t * (height - apex)
            out += Spark(cx, y, 0.9f)
            out += Spark(cx, y + height * 0.03f, 0.4f)
            return out
        }
        // Explosion sphere, drifting down with gravity, fading out.
        // Clamped to the frame: renderers draw 1:1 with these coordinates.
        val t = (phase - rise).toFloat() / (CYCLE - rise)
        val radius = (0.10f + energy * 0.22f) * width.coerceAtMost(height * 1.4f)
        for (s in 0 until SPOKES) {
            val a = 2 * PI * s / SPOKES + b
            val r = radius * (0.6f + 0.4f * ((b * 7 + s * 13) % 10) / 10f)
            out += Spark(
                (cx + (r * cos(a)).toFloat()).coerceIn(0f, width),
                (apex + (r * sin(a)).toFloat() + t * t * height * 0.25f).coerceIn(0f, height),
                (1f - t) * 0.95f,
            )
        }
        return out
    }

    /** Simultaneous burst count: 5 quiet, up to 14 loud. */
    fun burstCount(energy: Float): Int = (5 + energy * 9).toInt().coerceIn(5, 14)
}
