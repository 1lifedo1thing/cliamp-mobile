package stream.kleeamp.mobile.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.prefs.Prefs

class CliampChannelViewModel(
    private val channelId: String,
    private val repository: RadioRepository,
    private val prefs: Prefs,
) : ViewModel() {
    data class UiState(
        val channel: CliampChannels.Channel? = null,
        val tracks: List<Station> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val favorites: Set<String> = emptySet(),
    )

    sealed interface Event {
        data object Refresh : Event
        data class ToggleFavorite(val station: Station) : Event
    }

    private val _ui = MutableStateFlow(
        UiState(channel = repository.cliampChannels.value.firstOrNull { it.id == channelId }),
    )
    val state: StateFlow<UiState> = _ui.asStateFlow()

    private val reloadTick = MutableStateFlow(0)
    private var forceRefresh = false

    init {
        viewModelScope.launch {
            combine(repository.cliampChannels, prefs.favorites, reloadTick) { channels, favs, _ ->
                Triple(
                    channels.firstOrNull { it.id == channelId },
                    favs.mapTo(HashSet()) { it.url },
                    channels.isNotEmpty(),
                )
            }.collect { (channel, favUrls, loaded) ->
                if (channel == null) {
                    // Unknown id once the list landed; still loading while it hasn't.
                    _ui.value = UiState(
                        loading = !loaded,
                        error = if (loaded) "no such channel" else null,
                        favorites = favUrls,
                    )
                    return@collect
                }
                if (!channel.hasTracks) {
                    _ui.value = UiState(channel = channel, loading = false, favorites = favUrls)
                    return@collect
                }
                val refresh = forceRefresh.also { forceRefresh = false }
                if (_ui.value.tracks.isEmpty() || refresh) {
                    _ui.value = _ui.value.copy(channel = channel, loading = true, error = null, favorites = favUrls)
                } else {
                    _ui.value = _ui.value.copy(channel = channel, favorites = favUrls)
                    return@collect
                }
                val tracks = repository.cliampTracks(channelId, refresh)
                _ui.value = UiState(
                    channel = channel,
                    tracks = tracks,
                    loading = false,
                    error = if (tracks.isEmpty()) "couldn't read the track list" else null,
                    favorites = favUrls,
                )
            }
        }
    }

    fun onEvent(e: Event) {
        when (e) {
            is Event.ToggleFavorite -> viewModelScope.launch {
                prefs.toggleFavorite(e.station)
            }
            is Event.Refresh -> {
                forceRefresh = true
                reloadTick.value += 1
            }
        }
    }
}
