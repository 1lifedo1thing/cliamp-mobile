package stream.kleeamp.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.data.Prefs
import stream.kleeamp.mobile.data.Station
import stream.kleeamp.mobile.data.visualizer.StereoMetrics
import stream.kleeamp.mobile.playback.PlaybackBus
import stream.kleeamp.mobile.playback.PlayerConnection
import stream.kleeamp.mobile.playback.upNextIndices
import stream.kleeamp.mobile.playback.PlayerState

/** Tap-steps through the speed ladder, wrapping back to normal. */
private val SpeedSteps = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.5f, 0.75f)

private fun nextSpeed(now: Float): Float {
    val i = SpeedSteps.indexOfFirst { kotlin.math.abs(it - now) < 0.01f }
    return if (i < 0) 1f else SpeedSteps[(i + 1) % SpeedSteps.size]
}

class NowPlayingViewModel(
    val player: PlayerConnection,
    private val prefs: Prefs,
) : ViewModel() {
    data class UiState(
        val playerState: PlayerState = PlayerState(),
        val shownStation: Station? = null,
        val streamTitle: String = "",
        val upNextCount: Int = 0,
        val reconnect: Int = 0,
        val error: String? = null,
        val isFav: Boolean = false,
        val shuffled: Boolean = false,
        val visualizer: String = "spectrum",
        val spectrum: FloatArray = FloatArray(0),
        val stereo: StereoMetrics = StereoMetrics.silent,
    )

    sealed interface Event {
        data object ToggleFavorite : Event
        data object CycleSpeed : Event
    }

    /** Live transport and tune state: what is playing and what it says. */
    private data class BusState(
        val playerState: PlayerState = PlayerState(),
        val station: Station? = null,
        val streamTitle: String = "",
        val error: String? = null,
        val reconnect: Int = 0,
    )

    /** Library prefs and derived flags that dress the transport. */
    private data class LibState(
        val spectrum: FloatArray = FloatArray(0),
        val favorites: List<Station> = emptyList(),
        val recent: List<Station> = emptyList(),
        val visualizer: String = "spectrum",
        val shuffled: Boolean = false,
        val stereo: StereoMetrics = StereoMetrics.silent,
    )

    val state: StateFlow<UiState> = combine(
        combine(
            player.state,
            PlaybackBus.station,
            PlaybackBus.streamTitle,
            PlaybackBus.error,
            PlaybackBus.reconnectAttempt,
            ::BusState,
        ),
        combine(
            combine(
                PlaybackBus.spectrum,
                PlaybackBus.stereo,
            ) { spectrum, stereo -> spectrum to stereo },
            prefs.favorites,
            prefs.history,
            prefs.visualizer,
            player.shuffle,
        ) { spectrumStereo, favorites, history, visualizer, shuffled ->
            LibState(
                spectrum = spectrumStereo.first,
                favorites = favorites,
                recent = history,
                visualizer = visualizer,
                shuffled = shuffled,
                stereo = spectrumStereo.second,
            )
        },
        player.upNext,
        player.upNextIndex,
    ) { bus, lib, upNext, upNextIndex ->
        // Before anything has been played this session the live bus carries no
        // station, so fall back to the last-played station from history - the same
        // fallback the mini bar uses - rather than showing an empty "no track".
        val shownStation = bus.station ?: lib.recent.firstOrNull()
        val activeIndex = upNextIndex.takeIf { bus.station != null && upNext.getOrNull(it)?.url == bus.station.url } ?: -1
        UiState(
            playerState = bus.playerState,
            shownStation = shownStation,
            streamTitle = bus.streamTitle,
            upNextCount = upNextIndices(upNext.size, activeIndex).count(),
            reconnect = bus.reconnect,
            error = bus.error,
            isFav = shownStation != null && lib.favorites.any { it.url == shownStation.url },
            shuffled = lib.shuffled,
            visualizer = lib.visualizer,
            spectrum = lib.spectrum,
            stereo = lib.stereo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun onEvent(e: Event) {
        when (e) {
            Event.ToggleFavorite -> viewModelScope.launch {
                state.value.shownStation?.let { prefs.toggleFavorite(it) }
            }
            // Pure player writes with no logic (seek/prev/next/shuffle/play-pause)
            // stay as direct vm.player.* calls in the composable; only the speed
            // ladder, which derives the next step, is routed here.
            Event.CycleSpeed -> player.setSpeed(nextSpeed(player.speed.value))
        }
    }
}
