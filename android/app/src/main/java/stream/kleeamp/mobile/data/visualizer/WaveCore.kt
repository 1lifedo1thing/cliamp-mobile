package stream.kleeamp.mobile.data.visualizer

/**
 * The wave oscilloscope's state, ported from cliamp's braille waveform
 * (`ui/vis_wave.go`). cliamp downsamples raw time-domain samples to one
 * y-position per dot column and connects consecutive points; here the same
 * mapping feeds a Canvas path instead of braille cells, so the trace keeps
 * its oscilloscope character at any size.
 *
 * Waveform capture is 8-bit unsigned (128 is silence); [fromBytes] unwraps it
 * to -1..1. [trace] nearest-downsamples to y-fractions 0..1 (0 is the top),
 * exactly like the Go version - no smoothing, an authentic scope is jagged.
 * Empty input traces the center line, which is also the pause rest state.
 */
object WaveCore {

    /** Waveform bytes are unsigned: 128 is the center, 0/255 the rails. */
    fun fromBytes(wf: ByteArray): FloatArray {
        val out = FloatArray(wf.size)
        for (i in wf.indices) {
            out[i] = (wf[i].toInt() and 0xFF) / 128f - 1f
        }
        return out
    }

    /** One y-fraction per horizontal point; empty samples give the center line. */
    fun trace(samples: FloatArray, points: Int): FloatArray {
        val out = FloatArray(points) { 0.5f }
        if (samples.isEmpty() || points <= 0) return out
        for (x in 0 until points) {
            val idx = (x.toLong() * samples.size / points).toInt()
                .coerceIn(0, samples.lastIndex)
            out[x] = (1f - samples[idx].coerceIn(-1f, 1f)) / 2f
        }
        return out
    }
}
