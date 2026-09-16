package stream.cliamp.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.DirectoryState
import stream.cliamp.mobile.data.DownloadStore
import stream.cliamp.mobile.data.LocalLibrary
import stream.cliamp.mobile.data.PlaylistSort
import stream.cliamp.mobile.data.PlaylistStore
import stream.cliamp.mobile.data.PodcastRepository
import stream.cliamp.mobile.data.PodcastShow
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.Repository
import stream.cliamp.mobile.data.ShowState
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.data.sortedStations

class PlaylistDetailViewModel(
    private val slug: String,
    private val localLibrary: LocalLibrary,
    private val playlists: PlaylistStore,
    private val prefs: Prefs,
    private val repository: Repository,
    private val podcasts: PodcastRepository,
    private val downloads: DownloadStore,
) : ViewModel() {
    data class UiState(
        val playlist: PlaylistStore.Playlist? = null,
        val songIds: List<String> = emptyList(),
        val members: List<Station> = emptyList(),
        val visible: List<Station> = emptyList(),
        val sort: PlaylistSort = PlaylistSort.Title,
        val localSongs: List<Station> = emptyList(),
        val radioStations: List<Station> = emptyList(),
        val subscribedShows: List<PodcastShow> = emptyList(),
        val showState: ShowState = ShowState(),
        val favorites: List<Station> = emptyList(),
    )

    sealed interface Event {
        data class ToggleMember(val station: Station, val add: Boolean) : Event
        data class ToggleFavorite(val station: Station) : Event
        data class SetCover(val cover: String) : Event
        data class SetSort(val sort: PlaylistSort) : Event
        data class OpenShow(val show: PodcastShow) : Event
    }

    private data class CollectionState(
        val songs: List<Station> = emptyList(),
        val all: List<PlaylistStore.Playlist> = emptyList(),
        val subscriptions: List<PodcastShow> = emptyList(),
        val cliamp: List<Station> = emptyList(),
    )

    private data class MetaState(
        val directory: DirectoryState = DirectoryState(),
        val favorites: List<Station> = emptyList(),
        val sort: PlaylistSort = PlaylistSort.Title,
        val showState: ShowState = ShowState(),
    )

    val state: StateFlow<UiState> = combine(
        combine(
            localLibrary.songs,
            playlists.playlists,
            podcasts.subscriptions,
            repository.cliamp,
            ::CollectionState,
        ),
        combine(
            repository.directory,
            prefs.favorites,
            prefs.playlistSort(slug),
            podcasts.show,
            ::MetaState,
        ),
    ) { col, meta ->
        val pl = col.all.firstOrNull { it.station.slug == slug }
        val ids = pl?.songIds.orEmpty()
        val members = if (ids.isEmpty()) emptyList() else playlists.resolveMembers(ids, col.songs)
        val favRadio = meta.favorites.filterNot {
            it.source == StationSource.Local || it.source == StationSource.Podcast
        }
        UiState(
            playlist = pl,
            songIds = ids,
            members = members,
            visible = sortedStations(members, meta.sort),
            sort = meta.sort,
            localSongs = col.songs,
            radioStations = (col.cliamp + meta.directory.stations + favRadio).distinctBy { it.id },
            subscribedShows = col.subscriptions,
            showState = meta.showState,
            favorites = meta.favorites,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiState(
            sort = prefs.playlistSortValue(slug),
            localSongs = localLibrary.songs.value,
            showState = podcasts.show.value,
        ),
    )

    fun onEvent(e: Event) {
        when (e) {
            is Event.ToggleMember -> viewModelScope.launch {
                if (e.add) playlists.addStation(slug, e.station)
                else playlists.removeSong(slug, e.station.id)
            }
            is Event.ToggleFavorite -> viewModelScope.launch { prefs.toggleFavorite(e.station) }
            is Event.SetCover -> viewModelScope.launch { playlists.setCover(slug, e.cover) }
            is Event.SetSort -> prefs.setPlaylistSort(slug, e.sort)
            is Event.OpenShow -> podcasts.openShow(e.show)
        }
    }
}
