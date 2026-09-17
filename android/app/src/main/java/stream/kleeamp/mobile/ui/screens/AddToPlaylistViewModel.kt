package stream.kleeamp.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.data.LocalLibrary
import stream.kleeamp.mobile.data.PlaylistStore
import stream.kleeamp.mobile.data.Prefs
import stream.kleeamp.mobile.data.Repository
import stream.kleeamp.mobile.data.Station

/**
 * The add-to-playlist picker: one song, favourites plus every user playlist.
 *
 * There is no staged selection and no Done: every tap applies immediately —
 * tapping a playlist adds the song, tapping again removes it, and back just
 * leaves (everything is already saved). The checkmarks mirror membership, so
 * they flip as soon as the database flows echo the change.
 *
 * The song arrives as a playback URL (the same key [LibrarySongInfo] uses) and
 * is re-resolved against the live library, favourites/history, the radio
 * catalogues and the persisted playlist snapshots — in that order — so a song
 * tapped inside any playlist (a podcast episode or provider track snapshot
 * that was never favourited) still resolves.
 */
class AddToPlaylistViewModel(
    private val stationUrl: String,
    private val localLibrary: LocalLibrary,
    private val playlists: PlaylistStore,
    private val prefs: Prefs,
    private val repository: Repository,
) : ViewModel() {
    data class UiState(
        val song: Station? = null,
        /** True once every source has answered (a missing song then stays missing). */
        val songReady: Boolean = false,
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

    private val _snapshot = MutableStateFlow<Station?>(null)
    private val _snapshotReady = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            _snapshot.value = playlists.snapshotByUrl(stationUrl)
            _snapshotReady.value = true
        }
    }

    val state: StateFlow<UiState> = combine(
        combine(
            localLibrary.songs,
            prefs.favorites,
            prefs.history,
        ) { songs, favorites, recent -> Triple(songs, favorites, recent) },
        combine(
            repository.cliamp,
            repository.directory,
            playlists.playlists,
        ) { cliamp, directory, all -> Triple(cliamp, directory, all) },
        combine(
            _snapshot,
            _snapshotReady,
        ) { snapshot, snapshotReady -> snapshot to snapshotReady },
    ) { social, catalog, snapshotState ->
        val (songs, favorites, recent) = social
        val (cliamp, directory, all) = catalog
        val (snapshot, snapshotReady) = snapshotState
        val song = songs.firstOrNull { it.url == stationUrl }
            ?: favorites.firstOrNull { it.url == stationUrl }
            ?: recent.firstOrNull { it.url == stationUrl }
            ?: cliamp.firstOrNull { it.url == stationUrl }
            ?: directory.stations.firstOrNull { it.url == stationUrl }
            ?: snapshot
        val isFavorite = song?.let { s -> favorites.any { it.url == s.url } } ?: false
        UiState(
            song = song,
            songReady = song != null || snapshotReady,
            allPlaylists = all,
            memberOf = song?.let { s ->
                all.filter { s.id in it.songIds }.map { it.station.slug }.toSet()
            }.orEmpty(),
            favoritesCount = favorites.size,
            isFavorite = isFavorite,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
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
                val song = state.value.song ?: return
                if (state.value.allPlaylists.none { it.station.slug == e.slug }) return
                viewModelScope.launch(NonCancellable) {
                    playlists.toggleStation(e.slug, song)
                }
            }
            // A fresh playlist gains the song straight away, matching the
            // ticked row it arrives with.
            is Event.Create -> {
                val song = state.value.song ?: return
                viewModelScope.launch(NonCancellable) {
                    val slug = playlists.create(e.name) ?: return@launch
                    playlists.addStation(slug, song)
                }
            }
            Event.ToggleFavorites -> {
                val song = state.value.song ?: return
                viewModelScope.launch(NonCancellable) {
                    prefs.toggleFavorite(song)
                }
            }
        }
    }
}
