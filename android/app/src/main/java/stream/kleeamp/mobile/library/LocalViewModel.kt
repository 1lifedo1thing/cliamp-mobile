package stream.kleeamp.mobile.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.podcasts.DownloadEntry
import stream.kleeamp.mobile.library.LocalLibrary
import stream.kleeamp.mobile.library.PlaylistStore
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.servers.ProviderAccount
import stream.kleeamp.mobile.servers.ProviderStore
import stream.kleeamp.mobile.prefs.PlaylistSort

class LocalViewModel(
    private val localLibrary: LocalLibrary,
    private val playlists: PlaylistStore,
    private val prefs: Prefs,
    private val providers: ProviderStore,
) : ViewModel() {
    data class UiState(
        val songs: List<Station> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val allPlaylists: List<PlaylistStore.Playlist> = emptyList(),
        val pinnedSlugs: Set<String> = emptySet(),
        val pinnedPlaylists: List<PlaylistStore.Playlist> = emptyList(),
        val unpinnedPlaylists: List<PlaylistStore.Playlist> = emptyList(),
        val localSort: PlaylistSort = PlaylistSort.Title,
        val fetched: Map<String, DownloadEntry> = emptyMap(),
        val favorites: List<Station> = emptyList(),
        val recent: List<Station> = emptyList(),
        val providerAccounts: List<ProviderAccount> = emptyList(),
    )

    sealed interface Event {
        data class Create(val name: String) : Event
        data class Rename(val slug: String, val name: String) : Event
        data class Delete(val slug: String) : Event
        data class SetPinned(val slug: String, val pinned: Boolean) : Event
        data object Refresh : Event
    }

    private data class DeviceState(
        val songs: List<Station> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    private data class ListsState(
        val all: List<PlaylistStore.Playlist> = emptyList(),
        val pinned: Set<String> = emptySet(),
        val sort: PlaylistSort = PlaylistSort.Title,
        val fetched: Map<String, DownloadEntry> = emptyMap(),
    )

    private data class SocialState(
        val favorites: List<Station> = emptyList(),
        val recent: List<Station> = emptyList(),
        val providerAccounts: List<ProviderAccount> = emptyList(),
    )

    val state: StateFlow<UiState> = combine(
        combine(
            localLibrary.songs,
            localLibrary.loading,
            localLibrary.error,
            ::DeviceState,
        ),
        combine(
            playlists.playlists,
            playlists.pinnedSlugs,
            prefs.playlistSort("local-songs"),
            prefs.downloads,
            ::ListsState,
        ),
        combine(
            prefs.favorites,
            prefs.history,
            providers.accounts,
            ::SocialState,
        ),
    ) { device, lists, social ->
        UiState(
            songs = device.songs,
            loading = device.loading,
            error = device.error,
            allPlaylists = lists.all,
            pinnedSlugs = lists.pinned,
            pinnedPlaylists = lists.all.filter { it.station.slug in lists.pinned },
            unpinnedPlaylists = lists.all.filterNot { it.station.slug in lists.pinned },
            localSort = lists.sort,
            fetched = lists.fetched,
            favorites = social.favorites,
            recent = social.recent,
            providerAccounts = social.providerAccounts,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiState(
            songs = localLibrary.songs.value,
            loading = localLibrary.loading.value,
            error = localLibrary.error.value,
            localSort = prefs.playlistSortValue("local-songs"),
        ),
    )

    fun onEvent(e: Event) {
        when (e) {
            is Event.Create -> viewModelScope.launch { playlists.create(e.name) }
            is Event.Rename -> viewModelScope.launch { playlists.rename(e.slug, e.name) }
            is Event.Delete -> viewModelScope.launch { playlists.delete(e.slug) }
            is Event.SetPinned -> viewModelScope.launch { playlists.setPinned(e.slug, e.pinned) }
            Event.Refresh -> localLibrary.refresh()
        }
    }
}
