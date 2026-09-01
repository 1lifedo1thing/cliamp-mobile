package stream.cliamp.mobile.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import stream.cliamp.mobile.data.Station

/**
 * The service and the UI live in the same process, so rather than round-trip
 * the spectrum through a MediaSession custom command sixty times a second we
 * publish it on a plain in-process bus. Transport control still goes through
 * MediaController, which is what keeps the lockscreen and Bluetooth honest.
 */
object PlaybackBus {
    private val _spectrum = MutableStateFlow(FloatArray(0))
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()

    /** False means the columns are synthesised, not measured. */
    private val _spectrumLive = MutableStateFlow(false)
    val spectrumLive: StateFlow<Boolean> = _spectrumLive.asStateFlow()

    private val _streamTitle = MutableStateFlow("")
    val streamTitle: StateFlow<String> = _streamTitle.asStateFlow()

    private val _station = MutableStateFlow<Station?>(null)
    val station: StateFlow<Station?> = _station.asStateFlow()

    private val _format = MutableStateFlow(StreamFormat())
    val format: StateFlow<StreamFormat> = _format.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _audioSessionId = MutableStateFlow(0)
    val audioSessionId: StateFlow<Int> = _audioSessionId.asStateFlow()

    private val _eqBandLabels = MutableStateFlow<List<String>>(emptyList())
    val eqBandLabels: StateFlow<List<String>> = _eqBandLabels.asStateFlow()

    fun publishSpectrum(v: FloatArray) { _spectrum.value = v }
    fun publishSpectrumLive(v: Boolean) { _spectrumLive.value = v }
    fun publishStreamTitle(v: String) { _streamTitle.value = v }
    fun publishStation(v: Station?) { _station.value = v; if (v != null) _streamTitle.value = "" }
    fun publishFormat(v: StreamFormat) { _format.value = v }
    fun publishError(v: String?) { _error.value = v }
    fun publishAudioSessionId(v: Int) { _audioSessionId.value = v }
    fun publishEqBandLabels(v: List<String>) { _eqBandLabels.value = v }
}

data class StreamFormat(
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val codec: String = "",
) {
    /** `mp3 · 128k · 44.1k` for the identity strip. */
    fun summary(fallback: String): String = buildList {
        if (codec.isNotBlank()) add(codec.lowercase())
        if (bitrateKbps > 0) add("${bitrateKbps}k")
        if (sampleRateHz > 0) add("%.1fk".format(sampleRateHz / 1000f))
    }.joinToString(" · ").ifBlank { fallback }
}
