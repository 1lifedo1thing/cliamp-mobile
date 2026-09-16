package stream.cliamp.mobile.ui.screens

import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.DownloadEntry
import stream.cliamp.mobile.data.DownloadStore
import stream.cliamp.mobile.data.DirectoryState
import stream.cliamp.mobile.data.LocalLibrary
import stream.cliamp.mobile.data.PlaylistSort
import stream.cliamp.mobile.data.PodcastRepository
import stream.cliamp.mobile.data.PodcastShow
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.Repository
import stream.cliamp.mobile.data.ShowState
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource

class SmartPlaylistViewModel(
    val kindName: String,
    private val localLibrary: LocalLibrary,
    private val prefs: Prefs,
    private val downloads: DownloadStore,
    private val repository: Repository,
    private val podcasts: PodcastRepository,
) : ViewModel() {
    val kind: SmartKind? = SmartKind.entries.firstOrNull { it.name == kindName }
    private val sortKey: String = if (kind == SmartKind.Downloads) "downloads" else "local-songs"

    private val frozenRecent = MutableStateFlow<List<Station>?>(null)

    init {
        viewModelScope.launch {
            prefs.history.collect { rec ->
                if (kind == SmartKind.RecentlyPlayed && frozenRecent.value == null && rec.isNotEmpty()) {
                    frozenRecent.value = rec
                }
            }
        }
    }

    data class UiState(
        val kind: SmartKind? = null,
        val songs: List<Station> = emptyList(),
        val loading: Boolean = false,
        val localSort: PlaylistSort = PlaylistSort.Title,
        val detailSort: PlaylistSort = PlaylistSort.Title,
        val fetched: Map<String, DownloadEntry> = emptyMap(),
        val favorites: List<Station> = emptyList(),
        val recent: List<Station> = emptyList(),
        val viewRecent: List<Station> = emptyList(),
        val resumeLocal: Boolean = false,
        val radioStations: List<Station> = emptyList(),
        val subscribedShows: List<PodcastShow> = emptyList(),
        val showState: ShowState = ShowState(),
    )

    sealed interface Event {
        data class ToggleFavorite(val station: Station) : Event
        data class SetFavorite(val station: Station, val add: Boolean) : Event
        data class DeleteLocal(val station: Station) : Event
        data class RemoveDownload(val station: Station) : Event
        data class SetSort(val sort: PlaylistSort) : Event
        data class OpenShow(val show: PodcastShow) : Event
    }

    private data class DeviceState(
        val songs: List<Station> = emptyList(),
        val loading: Boolean = false,
        val localSort: PlaylistSort = PlaylistSort.Title,
        val detailSort: PlaylistSort = PlaylistSort.Title,
        val fetched: Map<String, DownloadEntry> = emptyMap(),
    )

    private data class SocialState(
        val favorites: List<Station> = emptyList(),
        val recent: List<Station> = emptyList(),
        val resumeLocal: Boolean = false,
        val frozen: List<Station>? = null,
    )

    private data class CatalogState(
        val cliamp: List<Station> = emptyList(),
        val directory: DirectoryState = DirectoryState(),
        val subscriptions: List<PodcastShow> = emptyList(),
        val showState: ShowState = ShowState(),
    )

    val state: StateFlow<UiState> = combine(
        combine(
            localLibrary.songs,
            localLibrary.loading,
            prefs.playlistSort("local-songs"),
            prefs.playlistSort(sortKey),
            prefs.downloads,
            ::DeviceState,
        ),
        combine(
            prefs.favorites,
            prefs.history,
            prefs.resumeLocal,
            frozenRecent,
            ::SocialState,
        ),
        combine(
            repository.cliamp,
            repository.directory,
            podcasts.subscriptions,
            podcasts.show,
            ::CatalogState,
        ),
    ) { device, social, catalog ->
        val favRadio = social.favorites.filterNot {
            it.source == StationSource.Local || it.source == StationSource.Podcast
        }
        UiState(
            kind = kind,
            songs = device.songs,
            loading = device.loading,
            localSort = device.localSort,
            detailSort = device.detailSort,
            fetched = device.fetched,
            favorites = social.favorites,
            recent = social.recent,
            viewRecent = if (kind == SmartKind.RecentlyPlayed) {
                social.frozen ?: social.recent
            } else {
                social.recent
            },
            resumeLocal = social.resumeLocal,
            radioStations = (catalog.cliamp + catalog.directory.stations + favRadio)
                .distinctBy { it.id },
            subscribedShows = catalog.subscriptions,
            showState = catalog.showState,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiState(
            kind = kind,
            songs = localLibrary.songs.value,
            loading = localLibrary.loading.value,
            localSort = prefs.playlistSortValue("local-songs"),
            detailSort = prefs.playlistSortValue(sortKey),
        ),
    )

    fun deleteRequest(s: Station): PendingIntent? = localLibrary.deleteRequest(s)

    fun onEvent(e: Event) {
        when (e) {
            is Event.ToggleFavorite -> viewModelScope.launch { prefs.toggleFavorite(e.station) }
            is Event.SetFavorite -> viewModelScope.launch {
                if (e.add) prefs.addFavorite(e.station)
                else prefs.removeFavorite(e.station)
            }
            is Event.DeleteLocal -> viewModelScope.launch {
                localLibrary.removeLocal(e.station)
                prefs.removeFavorite(e.station)
            }
            is Event.RemoveDownload -> downloads.remove(e.station.url)
            is Event.SetSort -> prefs.setPlaylistSort(sortKey, e.sort)
            is Event.OpenShow -> podcasts.openShow(e.show)
        }
    }
}
