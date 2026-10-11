package stream.kleeamp.mobile.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.prefs.Prefs

/**
 * Every track of one artist: the library songs plus the members of the
 * list the artist was opened from, so playlist-only stations (radio,
 * podcasts) come along too.
 */
class ArtistViewModel(
    val artist: String,
    private val context: String,
    private val localLibrary: LocalLibrary,
    private val playlists: PlaylistStore,
    private val prefs: Prefs,
) : ViewModel() {
    data class UiState(
        val songs: List<Station> = emptyList(),
        val favorites: List<Station> = emptyList(),
    )

    sealed interface Event {
        data class ToggleFavorite(val station: Station) : Event
    }

    val state: StateFlow<UiState> = combine(
        localLibrary.songs,
        playlists.playlists,
        prefs.favorites,
    ) { songs, all, favorites ->
        val members = all.firstOrNull { it.station.slug == context }?.let {
            playlists.resolveMembers(it.songIds, songs)
        }.orEmpty()
        val mine = (songs + members).distinctBy { it.id }
            .filter { it.artist.ifBlank { "unknown artist" } == artist }
        UiState(songs = mine, favorites = favorites)
    }.stateInUi(viewModelScope, UiState())

    fun onEvent(e: Event) {
        when (e) {
            is Event.ToggleFavorite -> viewModelScope.launch { prefs.toggleFavorite(e.station) }
        }
    }
}
