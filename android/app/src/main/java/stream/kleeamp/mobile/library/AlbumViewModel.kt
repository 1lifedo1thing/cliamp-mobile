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
 * Every track of one album within the list the album was opened from.
 * Context "local-songs" scopes to the on-device library; otherwise the
 * source is exactly that user playlist's resolved members. The album is
 * keyed by album + album artist so compilations stay one album while
 * same-named albums from different album artists stay apart. Songs play in
 * disc/track order.
 */
class AlbumViewModel(
    val album: String,
    val albumArtist: String,
    private val context: String,
    private val localLibrary: LocalLibrary,
    private val playlists: PlaylistStore,
    private val prefs: Prefs,
) : ViewModel() {
    data class UiState(
        val songs: List<Station> = emptyList(),
        val favorites: List<Station> = emptyList(),
        /** Shown under the album title, same rule as the albums list. */
        val displayArtist: String = "",
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
        val source = if (context == "local-songs") songs else members
        val wantAlbum = album.ifBlank { "unknown album" }
        val mine = source.distinctBy { it.id }
            .filter {
                it.album.ifBlank { "unknown album" } == wantAlbum &&
                    it.albumArtist == albumArtist
            }
            .sortedWith(
                compareBy<Station> { it.discNo }.thenBy { it.trackNo }.thenBy { it.name.lowercase() },
            )
        UiState(
            songs = mine,
            favorites = favorites,
            displayArtist = albumDisplayArtist(albumArtist, mine),
        )
    }.stateInUi(viewModelScope, UiState())

    fun onEvent(e: Event) {
        when (e) {
            is Event.ToggleFavorite -> viewModelScope.launch { prefs.toggleFavorite(e.station) }
        }
    }
}
