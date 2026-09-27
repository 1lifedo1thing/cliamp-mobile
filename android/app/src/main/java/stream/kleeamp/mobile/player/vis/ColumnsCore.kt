package stream.kleeamp.mobile.player.vis

/**
 * Dense thin columns: the spectrum interpolates across many narrow bars so
 * neighbours vary slightly, mirroring cliamp's columns renderer.
 */
object ColumnsCore {

    /** Per-column levels interpolated to [count] thin bars. */
    fun columns(bands: FloatArray, count: Int): FloatArray =
        VisMath.resampleLinear(bands, count)

    /** Fractional fill of the top segment, for a partial-block cap. */
    fun topFrac(level: Float, segments: Int): Float {
        if (segments <= 0) return 0f
        val scaled = level.coerceIn(0f, 1f) * segments
        return (scaled - scaled.toInt()).coerceIn(0f, 1f)
    }
}
