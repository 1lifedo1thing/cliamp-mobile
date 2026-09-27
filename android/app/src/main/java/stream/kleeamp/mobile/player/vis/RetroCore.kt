package stream.kleeamp.mobile.player.vis

/**
 * Retro synthwave scene: a striped setting sun over a horizon, an
 * audio-reactive wave above it, and a perspective grid floor scrolling
 * toward the viewer, mirroring cliamp's renderRetro.
 */
object RetroCore {

    /** Wave heights (0..1) resampled to [points] columns. */
    fun wavePoints(bands: FloatArray, points: Int): FloatArray =
        VisMath.resampleLinear(bands, points).map { it.coerceIn(0f, 1f) }.toFloatArray()

    /** Floor scroll phase (0..1), one full approach per cycle. */
    fun scrollPhase(frame: Long): Double = ((frame * 0.08) % 1.0 + 1.0) % 1.0
}
