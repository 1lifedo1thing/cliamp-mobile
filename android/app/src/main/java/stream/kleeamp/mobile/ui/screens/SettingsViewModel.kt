package stream.kleeamp.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.radio.DirectoryStats
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.radio.RadioRepository
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.theme.KleeampPalette
import stream.kleeamp.mobile.theme.decodeCustomThemeOrNull
import stream.kleeamp.mobile.theme.customThemeNameOrNull
import stream.kleeamp.mobile.theme.parseCustomTheme

class SettingsViewModel(
    private val prefs: Prefs,
    private val repository: RadioRepository,
) : ViewModel() {
    data class UiState(
        val palette: String = "dark",
        val custom: KleeampPalette? = null,
        val customName: String? = null,
        val importError: String? = null,
        val haptics: Boolean = true,
        val visualizer: String = "spectrum",
        val cellular: Boolean = true,
        val mono: Boolean = false,
        val buffer: Int = 20,
        val autoResume: Boolean = false,
        val autoDownload: Boolean = false,
        val resumeLocal: Boolean = false,
        val listenBrainzOn: Boolean = false,
        val favoritesCount: Int = 0,
        val historyCount: Int = 0,
        val directoryStats: DirectoryStats? = null,
    )

    sealed interface Event {
        data class SetPalette(val key: String) : Event
        data class SetAutoResume(val v: Boolean) : Event
        data class SetResumeLocal(val v: Boolean) : Event
        data class SetAutoDownload(val v: Boolean) : Event
        data class SetCellular(val v: Boolean) : Event
        data class SetMono(val v: Boolean) : Event
        data class SetBuffer(val seconds: Int) : Event
        data class SetHaptics(val v: Boolean) : Event
        data class SetVisualizer(val v: String) : Event
        data class ImportTheme(val raw: String?) : Event
        data object ClearCustomTheme : Event
        data object ClearHistory : Event
    }

    private val importError = MutableStateFlow<String?>(null)

    /** Theme, feel and import state. */
    private data class Appearance(
        val palette: String = "dark",
        val customJson: String = "",
        val importError: String? = null,
        val haptics: Boolean = true,
        val visualizer: String = "spectrum",
    )

    /** Playback behaviour prefs. */
    private data class Playback(
        val cellular: Boolean = true,
        val mono: Boolean = false,
        val buffer: Int = 20,
        val autoResume: Boolean = false,
        val autoDownload: Boolean = false,
    )

    /** Library counts, scrobble flag and directory stats. */
    private data class Library(
        val resumeLocal: Boolean = false,
        val listenBrainzToken: String = "",
        val favorites: List<Station> = emptyList(),
        val history: List<Station> = emptyList(),
        val directoryStats: DirectoryStats? = null,
    )

    val state: StateFlow<UiState> = combine(
        combine(
            prefs.palette,
            prefs.customTheme,
            importError,
            prefs.haptics,
            prefs.visualizer,
            ::Appearance,
        ),
        combine(
            prefs.cellular,
            prefs.mono,
            prefs.bufferSeconds,
            prefs.autoResume,
            prefs.autoDownload,
            ::Playback,
        ),
        combine(
            prefs.resumeLocal,
            prefs.listenBrainzToken,
            prefs.favorites,
            prefs.history,
            repository.directoryStats,
            ::Library,
        ),
    ) { appearance, playback, library ->
        UiState(
            palette = appearance.palette,
            custom = decodeCustomThemeOrNull(appearance.customJson),
            customName = customThemeNameOrNull(appearance.customJson),
            importError = appearance.importError,
            haptics = appearance.haptics,
            visualizer = appearance.visualizer,
            cellular = playback.cellular,
            mono = playback.mono,
            buffer = playback.buffer,
            autoResume = playback.autoResume,
            autoDownload = playback.autoDownload,
            resumeLocal = library.resumeLocal,
            listenBrainzOn = library.listenBrainzToken.isNotBlank(),
            favoritesCount = library.favorites.size,
            historyCount = library.history.size,
            directoryStats = library.directoryStats,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun onEvent(e: Event) {
        when (e) {
            is Event.SetPalette -> viewModelScope.launch { prefs.setPalette(e.key) }
            is Event.SetAutoResume -> viewModelScope.launch { prefs.setAutoResume(e.v) }
            is Event.SetResumeLocal -> viewModelScope.launch { prefs.setResumeLocal(e.v) }
            is Event.SetAutoDownload -> viewModelScope.launch { prefs.setAutoDownload(e.v) }
            is Event.SetCellular -> viewModelScope.launch { prefs.setCellular(e.v) }
            is Event.SetMono -> viewModelScope.launch { prefs.setMono(e.v) }
            is Event.SetBuffer -> viewModelScope.launch { prefs.setBufferSeconds(e.seconds) }
            is Event.SetHaptics -> viewModelScope.launch { prefs.setHaptics(e.v) }
            is Event.SetVisualizer -> viewModelScope.launch { prefs.setVisualizer(e.v) }
            is Event.ImportTheme -> viewModelScope.launch {
                val raw = e.raw
                if (raw.isNullOrBlank()) {
                    importError.value = "could not read that file"
                    return@launch
                }
                parseCustomTheme(raw).fold(
                    onSuccess = {
                        importError.value = null
                        prefs.setCustomTheme(raw)
                        prefs.setPalette("custom")
                    },
                    onFailure = { importError.value = it.message ?: "not a theme file" },
                )
            }
            Event.ClearCustomTheme -> viewModelScope.launch { prefs.clearCustomTheme() }
            Event.ClearHistory -> viewModelScope.launch { prefs.clearHistory() }
        }
    }
}
