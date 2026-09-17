package stream.kleeamp.mobile.playback

import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow

/**
 * Real spectrum and real EQ, both attached to the ExoPlayer audio session.
 *
 * The Visualizer gives 1024-point FFT data; we fold it into however many
 * columns the meter is drawing, on a log frequency axis, because a linear
 * axis puts 90% of the columns above 5 kHz where music has almost no energy.
 */
class AudioFx(private val bands: Int = 64) {

    private var visualizer: Visualizer? = null
    private var equalizer: Equalizer? = null
    private var sessionId = 0
    private var spectrumOn = false

    val bandLabels: List<String> get() = _bandLabels
    private var _bandLabels: List<String> = emptyList()

    /** 7 sliders in the UI mapped onto however many bands the device gives us. */
    val uiBands = listOf(60, 150, 400, 1_000, 3_000, 8_000, 16_000)

    /** True once the Visualizer is attached and actually delivering frames. */
    var spectrumLive: Boolean = false
        private set

    fun attach(
        audioSessionId: Int,
        spectrumEnabled: Boolean,
        onSpectrum: (FloatArray) -> Unit,
        onLiveChanged: (Boolean) -> Unit = {},
    ) {
        if (audioSessionId == 0) return
        if (audioSessionId == sessionId && spectrumEnabled == spectrumOn) return
        release()
        sessionId = audioSessionId
        spectrumOn = spectrumEnabled

        runCatching {
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = false
                _bandLabels = (0 until numberOfBands).map { b ->
                    val hz = getCenterFreq(b.toShort()) / 1000
                    if (hz >= 1000) "${hz / 1000}k" else "$hz"
                }
            }
        }.onFailure { Log.w(TAG, "equalizer unavailable: ${it.message}") }

        if (!spectrumEnabled) {
            spectrumLive = false
            onLiveChanged(false)
            return
        }

        runCatching {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, wf: ByteArray?, rate: Int) = Unit
                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                            if (fft == null) return
                            if (!spectrumLive) {
                                spectrumLive = true
                                onLiveChanged(true)
                            }
                            onSpectrum(fold(fft))
                        }
                    },
                    Visualizer.getMaxCaptureRate().coerceAtMost(20_000),
                    false, true,
                )
                enabled = true
            }
        }.onSuccess {
            Log.d("kleeamp/wid", "ATTACH visualizer ok session=$audioSessionId")
        }.onFailure {
            Log.w(TAG, "visualizer unavailable (RECORD_AUDIO?): ${it.message}")
            visualizer = null
            spectrumLive = false
            onLiveChanged(false)
        }
    }

    /**
     * FFT layout is [Re(0), Re(n/2), Re(1), Im(1), ...]. We take magnitudes,
     * convert to dB, then bucket bins geometrically so each column covers a
     * constant ratio of frequency rather than a constant slice of it.
     */
    private fun fold(fft: ByteArray): FloatArray {
        val bins = fft.size / 2
        val out = FloatArray(bands)
        if (bins < 4) return out
        var lo = 1
        for (c in 0 until bands) {
            val hi = (bins.toDouble().pow((c + 1).toDouble() / bands)).toInt().coerceIn(lo + 1, bins)
            var peak = 0.0
            for (i in lo until hi) {
                val re = fft[i * 2].toInt().toDouble()
                val im = fft[i * 2 + 1].toInt().toDouble()
                val mag = hypot(re, im)
                if (mag > peak) peak = mag
            }
            // The Visualizer hands back 8-bit real/imaginary pairs, so a bin
            // tops out near hypot(127,127) and the smallest non-zero value is
            // already the quantisation floor. Referencing half-scale over 72 dB
            // (as we first did) lit a magnitude of 1 to 41%, which is why every
            // column sat high no matter what was playing. Reference full scale
            // over the ~48 dB the format can actually represent.
            val norm = (peak / FULL_SCALE).coerceIn(1e-4, 1.0)
            val db = 20 * log10(norm)
            out[c] = ((db + DYNAMIC_RANGE_DB) / DYNAMIC_RANGE_DB).coerceIn(0.0, 1.0).toFloat()
            lo = hi
        }
        return out
    }

    fun setEqEnabled(on: Boolean) {
        runCatching { equalizer?.enabled = on }
    }

    /** Slider values are -1..1; the device reports its own millibel range. */
    fun setBand(uiIndex: Int, value: Float) {
        val eq = equalizer ?: return
        runCatching {
            val range = eq.bandLevelRange
            val min = range[0].toInt()
            val max = range[1].toInt()
            val target = uiBands.getOrNull(uiIndex) ?: return
            val band = eq.getBand(target * 1000)
            if (band < 0) return
            val level = (((value + 1f) / 2f) * (max - min) + min).toInt().toShort()
            eq.setBandLevel(band, level)
        }
    }

    fun applyBands(values: List<Float>) = values.forEachIndexed { i, v -> setBand(i, v) }

    fun release() {
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        runCatching { equalizer?.release() }
        visualizer = null
        equalizer = null
        sessionId = 0
        spectrumOn = false
        spectrumLive = false
    }

    private companion object {
        const val TAG = "kleeamp/fx"

        /** hypot(127, 127) - the largest magnitude an 8-bit FFT bin can hold. */
        const val FULL_SCALE = 180.0

        /** 8-bit bins carry roughly 45 dB; 48 leaves a little headroom. */
        const val DYNAMIC_RANGE_DB = 48.0
    }
}

/**
 * The presets the concept names (FLAT / ROCK / HEADPHONE) plus the CLI's
 * sixteen, folded from its 10 bands (70Hz–16kHz, dB) onto these 7 by
 * nearest band (400Hz and 8kHz average their neighbours), scaled ÷12.
 */
object EqPresets {
    val flat = List(7) { 0f }
    val rock = listOf(0.42f, 0.33f, 0.04f, -0.17f, 0.17f, 0.38f, 0.42f)
    val pop = listOf(-0.08f, 0.17f, 0.38f, 0.33f, 0.08f, -0.08f, 0.17f)
    val jazz = listOf(0.25f, 0.33f, 0.12f, -0.08f, -0.08f, 0.12f, 0.33f)
    val classical = listOf(0.25f, 0.17f, 0.04f, -0.08f, -0.08f, 0.08f, 0.33f)
    val bass = listOf(0.67f, 0.5f, 0.25f, 0f, 0f, 0f, 0f)
    val treble = listOf(0f, 0f, 0f, 0f, 0.08f, 0.33f, 0.58f)
    val vocal = listOf(-0.17f, -0.08f, 0.21f, 0.42f, 0.33f, 0.08f, -0.17f)
    val electronic = listOf(0.5f, 0.33f, 0f, -0.17f, 0.08f, 0.29f, 0.5f)
    val acoustic = listOf(0.25f, 0.25f, 0.08f, 0.08f, 0.17f, 0.25f, 0.08f)
    val hiphop = listOf(0.58f, 0.42f, 0.17f, -0.08f, -0.08f, 0.17f, 0.25f)
    val rnb = listOf(0.33f, 0.5f, 0.17f, -0.08f, 0.08f, 0.17f, 0f)
    val loudness = listOf(0.5f, 0.33f, 0.04f, -0.17f, -0.08f, 0.21f, 0.42f)
    val latenight = listOf(0.42f, 0.25f, 0.04f, -0.17f, -0.08f, 0.08f, 0.25f)
    val podcast = listOf(-0.25f, -0.08f, 0.25f, 0.33f, 0.25f, 0f, -0.25f)
    val speakers = listOf(0.58f, 0.42f, 0.25f, 0.08f, 0f, -0.04f, 0.17f)
    val headphone = listOf(0.35f, 0.18f, 0f, 0.1f, 0.16f, 0.3f, 0.45f)

    val names = listOf(
        "flat", "rock", "pop", "jazz", "classical", "bass", "treble",
        "vocal", "electronic", "acoustic", "hip-hop", "r&b", "loudness",
        "late night", "podcast", "speakers", "headphone",
    )

    fun byName(name: String): List<Float>? = when (name) {
        "flat" -> flat
        "rock" -> rock
        "pop" -> pop
        "jazz" -> jazz
        "classical" -> classical
        "bass" -> bass
        "treble" -> treble
        "vocal" -> vocal
        "electronic" -> electronic
        "acoustic" -> acoustic
        "hip-hop" -> hiphop
        "r&b" -> rnb
        "loudness" -> loudness
        "late night" -> latenight
        "podcast" -> podcast
        "speakers" -> speakers
        "headphone" -> headphone
        else -> null
    }
}
