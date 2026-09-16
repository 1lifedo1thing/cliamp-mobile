package stream.cliamp.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.LocalLibrary
import stream.cliamp.mobile.data.PlaylistStore
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.Repository
import stream.cliamp.mobile.data.Station

/**
 * The add-to-playlist picker: one song, every user playlist, multi-select.
 *
 * The song arrives as a playback URL (the same key [LibrarySongInfo] uses) and
 * is re-resolved against the live library, favourites/history, the radio
 * catalogues and the persisted playlist snapshots — in that order — so a song
 * tapped inside any playlist (a podcast episode or provider track snapshot
 * that was never favourited) still resolves. Selection lives here rather than
 * in the pane so it survives rotation; Done fans the song out to every
 * selected playlist and flips [UiState.saved] for the pane to pop on.
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
        /** Slugs that already hold the song, shown as "already added". */
        val alreadyIn: Set<String> = emptySet(),
        val selected: Set<String> = emptySet(),
        val favoritesCount: Int = 0,
        val alreadyFavorite: Boolean = false,
        val favoritesSelected: Boolean = false,
        val saving: Boolean = false,
        val saved: Boolean = false,
    )

    sealed interface Event {
        data class Toggle(val slug: String) : Event
        data class Create(val name: String) : Event
        data object ToggleFavorites : Event
        data object Save : Event
    }

    private val _snapshot = MutableStateFlow<Station?>(null)
    private val _snapshotReady = MutableStateFlow(false)
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    private val _favoritesSelected = MutableStateFlow(false)
    private val _saving = MutableStateFlow(false)
    private val _saved = MutableStateFlow(false)

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
            _selected,
        ) { snapshot, snapshotReady, selected -> Triple(snapshot, snapshotReady, selected) },
        combine(_saving, _saved, _favoritesSelected) { saving, saved, fav -> Triple(saving, saved, fav) },
    ) { social, catalog, selection, finishing ->
        val (songs, favorites, recent) = social
        val (cliamp, directory, all) = catalog
        val (snapshot, snapshotReady, selected) = selection
        val (saving, saved, favoritesSelected) = finishing
        val song = songs.firstOrNull { it.url == stationUrl }
            ?: favorites.firstOrNull { it.url == stationUrl }
            ?: recent.firstOrNull { it.url == stationUrl }
            ?: cliamp.firstOrNull { it.url == stationUrl }
            ?: directory.stations.firstOrNull { it.url == stationUrl }
            ?: snapshot
        UiState(
            song = song,
            songReady = song != null || snapshotReady,
            allPlaylists = all,
            alreadyIn = song?.let { s ->
                all.filter { s.id in it.songIds }.map { it.station.slug }.toSet()
            }.orEmpty(),
            selected = selected,
            favoritesCount = favorites.size,
            alreadyFavorite = song?.let { s -> favorites.any { it.url == s.url } } ?: false,
            favoritesSelected = favoritesSelected,
            saving = saving,
            saved = saved,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiState(),
    )

    fun onEvent(e: Event) {
        when (e) {
            is Event.Toggle -> {
                _selected.value =
                    if (e.slug in _selected.value) _selected.value - e.slug
                    else _selected.value + e.slug
            }
            // A fresh playlist arrives already selected, so creating one is
            // immediately followed by Done (or by creating another, which
            // joins the selection the same way) with no extra taps.
            is Event.Create -> viewModelScope.launch {
                playlists.create(e.name)?.let { slug ->
                    _selected.value = _selected.value + slug
                }
            }
            Event.ToggleFavorites -> {
                _favoritesSelected.value = !_favoritesSelected.value
            }
            Event.Save -> {
                if (_saving.value || _saved.value ||
                    (_selected.value.isEmpty() && !_favoritesSelected.value)
                ) return
                val song = state.value.song ?: return
                viewModelScope.launch {
                    _saving.value = true
                    if (_favoritesSelected.value) prefs.addFavorite(song)
                    val known = state.value.allPlaylists.map { it.station.slug }.toSet()
                    playlists.addToPlaylists(song, _selected.value.intersect(known))
                    _saving.value = false
                    _saved.value = true
                }
            }
        }
    }
}
