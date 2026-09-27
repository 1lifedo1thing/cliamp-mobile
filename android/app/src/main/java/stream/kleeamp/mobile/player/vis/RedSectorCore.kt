package stream.kleeamp.mobile.player.vis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wireframe equalizer tumbling as a rigid body, after cliamp's Red Sector
 * homage: five hollow bars on a ground line driven by spectrum pairs,
 * rotating around the vertical axis while a starfield drifts behind.
 * Plain 3D math on a fixed virtual stage; renderers project to pixels.
 */
object RedSectorCore {

    /** Five bar heights (0..1) from spectrum pairs. */
    fun barHeights(bands: FloatArray): FloatArray {
        val out = FloatArray(5)
        for (b in 0 until 5) {
            val a = VisMath.sampleLinear(bands, b * 2f)
            val c = VisMath.sampleLinear(bands, b * 2f + 1f)
            out[b] = ((a + c) / 2f).coerceIn(0f, 1f)
        }
        return out
    }

    /** Tumble angle in radians, advancing with [frame]. */
    fun angle(frame: Long): Double = frame * 0.02

    /** Rotate (x, z) around Y by [angle]. */
    fun rotY(x: Double, z: Double, angle: Double): Pair<Double, Double> {
        val c = cos(angle)
        val s = sin(angle)
        return (x * c + z * s) to (-x * s + z * c)
    }

    /**
     * Star [i] drifting behind the bars, in -1..1 stage space.
     * Three depth lanes give the field its parallax.
     */
    fun star(i: Int, frame: Long): Triple<Double, Double, Double> {
        val lane = (i % 3).toDouble()
        val speed = 0.10 + lane * 0.09
        val x = (((i * 0.6180339887) % 1.0 + 1.0) % 1.0) * 2 - 1
        val y = (((i * 0.3819660113) % 1.0 + 1.0) % 1.0) * 2 - 1
        val drift = (frame * speed / 100.0) % 2.0
        var sx = x - drift
        if (sx < -1.0) sx += 2.0
        return Triple(sx, y, 0.3 + lane * 0.35)
    }

    /** Project stage (-1..1) to pixels. */
    fun project(
        x: Double,
        y: Double,
        width: Float,
        height: Float,
    ): Pair<Float, Float> = (
        ((x * 0.5 + 0.5) * width).toFloat() to
            ((0.5 - y * 0.5) * height).toFloat()
        )
}
