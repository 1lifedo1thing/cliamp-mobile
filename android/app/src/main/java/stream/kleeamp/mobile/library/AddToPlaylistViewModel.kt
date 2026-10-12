package stream.kleeamp.mobile.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.model.Station

/**
 * The add-to-playlist picker: one song, favourites plus every user playlist.
 *
 * There is no staged selection and no Done: every tap applies immediately —
 * tapping a playlist adds the song, tapping again removes it, and dismiss just
 * leaves (everything is already saved). The checkmarks mirror membership, so
 * they flip as soon as the database flows echo the change.
 *
 * The song arrives as the tapped [Station] itself (not a URL to re-resolve),
 * so a provider track, podcast episode or local song tapped anywhere always
 * resolves — there is no "song gone" page.
 */
class AddToPlaylistViewModel(
    val song: Station,
    private val playlists: PlaylistStore,
    private val prefs: Prefs,
) : ViewModel() {
    data class UiState(
        val allPlaylists: List<PlaylistStore.Playlist> = emptyList(),
        /** Slugs holding the song: the ticked rows. */
        val memberOf: Set<String> = emptySet(),
        val favoritesCount: Int = 0,
        val isFavorite: Boolean = false,
    )

    sealed interface Event {
        data class Toggle(val slug: String) : Event
        data class Create(val name: String) : Event
        data object ToggleFavorites : Event
    }

    val state: StateFlow<UiState> = combine(
        prefs.favorites,
        playlists.playlists,
    ) { favorites, all ->
        val isFavorite = favorites.any { it.id == song.id }
        UiState(
            allPlaylists = all,
            memberOf = all.filter { song.id in it.songIds }.map { it.station.slug }.toSet(),
            favoritesCount = favorites.size,
            isFavorite = isFavorite,
        )
    }.stateInUi(
        viewModelScope,
        UiState(),
    )

    fun onEvent(e: Event) {
        when (e) {
            // Every tap applies: direction resolves store-side from fresh
            // state under a lock, so rapid taps invert strictly in order
            // instead of racing on a stale snapshot — and nothing is ever
            // silently dropped while a write is in flight. Writes are not
            // cancellable by navigating away: a tap is user intent that
            // must land, not screen-owned work.
            is Event.Toggle -> {
                if (state.value.allPlaylists.none { it.station.slug == e.slug }) return
                viewModelScope.launch(NonCancellable) {
                    playlists.toggleStation(e.slug, song)
                }
            }
            // A fresh playlist gains the song straight away, matching the
            // ticked row it arrives with.
            is Event.Create -> {
                viewModelScope.launch(NonCancellable) {
                    val slug = playlists.create(e.name) ?: return@launch
                    playlists.addStation(slug, song)
                }
            }
            Event.ToggleFavorites -> {
                viewModelScope.launch(NonCancellable) {
                    prefs.toggleFavorite(song)
                }
            }
        }
    }
}
