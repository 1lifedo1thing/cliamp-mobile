package stream.kleeamp.mobile.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.podcasts.DownloadEntry
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
        data class Import(val filename: String, val text: String) : Event
        data object ClearImportReport : Event
    }

    /** Outcome of the last playlist-file import, until cleared. */
    data class ImportReport(
        val slug: String,
        val imported: Int,
        val missing: Int,
        val recognized: Boolean = true,
        /** Bumped per import so identical repeats still retrigger collectors. */
        val seq: Long = 0,
    )

    private val _importReport = MutableStateFlow<ImportReport?>(null)
    val importReport: StateFlow<ImportReport?> = _importReport.asStateFlow()
    private var importSeq = 0L

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
    }.stateInUi(
        viewModelScope,
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
            Event.ClearImportReport -> {
                _importReport.value = null
            }
            is Event.Import -> viewModelScope.launch(NonCancellable) {
                val kind = detectPlaylistKind(e.filename, e.text)
                if (kind == null) {
                    _importReport.value = ImportReport("", 0, 0, recognized = false, seq = ++importSeq)
                    return@launch
                }
                val entries = when (kind) {
                    PlaylistKind.M3U -> parseM3u(e.text)
                    PlaylistKind.PLS -> parsePls(e.text)
                }
                val match = matchEntries(entries, localLibrary.songs.value)
                val name = e.filename.substringAfterLast('/')
                    .substringBeforeLast('.').ifBlank { "imported" }
                val slug = playlists.importPlaylist(name, match.matched)
                _importReport.value = if (slug == null) {
                    ImportReport("", 0, match.missing + match.matched.size, seq = ++importSeq)
                } else {
                    ImportReport(slug, match.matched.size, match.missing, seq = ++importSeq)
                }
            }
        }
    }
}
