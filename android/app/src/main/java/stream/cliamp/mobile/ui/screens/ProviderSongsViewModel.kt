package stream.cliamp.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import stream.cliamp.mobile.data.PlaylistSort
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.provider.ProviderAccount
import stream.cliamp.mobile.data.provider.ProviderStore
import stream.cliamp.mobile.data.provider.browseClient
import stream.cliamp.mobile.data.provider.toStation

/**
 * The "providers" playlist: every connected account's songs, flat, with a
 * chip per account to narrow it down.
 *
 * Each account loads the way the browse screen does - its A-Z albums, then
 * each album's tracks - converted to [Station]s with the provider's own
 * cover URLs. Accounts load concurrently and fail independently: one
 * unreachable server leaves the others' songs in place and records its own
 * failure line rather than failing the whole list. Loaded songs are kept
 * per account id, so adding or removing an account only (re)loads what
 * changed instead of refetching every library.
 */
class ProviderSongsViewModel(
    private val store: ProviderStore,
    private val prefs: Prefs,
) : ViewModel() {
    data class UiState(
        val accounts: List<ProviderAccount> = emptyList(),
        val songsByAccount: Map<String, List<Station>> = emptyMap(),
        val loading: Boolean = false,
        val failures: Map<String, String> = emptyMap(),
        val sort: PlaylistSort = PlaylistSort.Title,
        val favorites: List<Station> = emptyList(),
    ) {
        val totalSongs: Int get() = songsByAccount.values.sumOf { it.size }
    }

    sealed interface Event {
        data object Refresh : Event
        data class SetSort(val sort: PlaylistSort) : Event
        data class ToggleFavorite(val station: Station) : Event
    }

    private val songsByAccount = MutableStateFlow<Map<String, List<Station>>>(emptyMap())
    private val failures = MutableStateFlow<Map<String, String>>(emptyMap())
    private val loading = MutableStateFlow(false)

    val state: StateFlow<UiState> = combine(
        combine(
            store.accounts,
            songsByAccount,
            failures,
            loading,
            ::SongsState,
        ),
        combine(
            prefs.playlistSort("providers"),
            prefs.favorites,
            ::PrefsState,
        ),
    ) { songs, prefsState ->
        UiState(
            accounts = songs.accounts,
            songsByAccount = songs.songs,
            loading = songs.loading,
            failures = songs.failures,
            sort = prefsState.sort,
            favorites = prefsState.favorites,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiState(sort = prefs.playlistSortValue("providers")),
    )

    private data class SongsState(
        val accounts: List<ProviderAccount> = emptyList(),
        val songs: Map<String, List<Station>> = emptyMap(),
        val failures: Map<String, String> = emptyMap(),
        val loading: Boolean = false,
    )

    private data class PrefsState(
        val sort: PlaylistSort = PlaylistSort.Title,
        val favorites: List<Station> = emptyList(),
    )

    init {
        viewModelScope.launch {
            store.accounts.collectLatest { accounts -> load(accounts) }
        }
    }

    fun onEvent(e: Event) {
        when (e) {
            Event.Refresh -> viewModelScope.launch { load(store.read()) }
            is Event.SetSort -> prefs.setPlaylistSort("providers", e.sort)
            is Event.ToggleFavorite -> viewModelScope.launch { prefs.toggleFavorite(e.station) }
        }
    }

    private suspend fun load(accounts: List<ProviderAccount>) {
        // Forget removed accounts; keep the rest so only new or changed
        // accounts fetch.
        val ids = accounts.map { it.id }.toSet()
        songsByAccount.value = songsByAccount.value.filterKeys { it in ids }
        failures.value = failures.value.filterKeys { it in ids }
        val missing = accounts.filter { it.id !in songsByAccount.value }
        if (missing.isEmpty()) return
        loading.value = true
        try {
            supervisorScope {
                missing.map { account ->
                    async {
                        runCatching { loadAccount(account) }
                            .onSuccess { songs ->
                                songsByAccount.value = songsByAccount.value + (account.id to songs)
                                failures.value = failures.value - account.id
                            }
                            .onFailure { t ->
                                failures.value = failures.value +
                                    (account.id to (t.message ?: "could not load"))
                            }
                    }
                }.forEach { it.await() }
            }
        } finally {
            loading.value = false
        }
    }

    private suspend fun loadAccount(account: ProviderAccount): List<Station> {
        val client = account.browseClient()
        val albums = client.albums("az").getOrThrow()
        // Station ids key the song list, so they must be unique: servers can
        // repeat a track across overlapping listings, and the same id twice
        // crashes the list outright.
        return albums.flatMap { album ->
            client.albumTracks(album.id).getOrDefault(emptyList())
                .map { it.toStation(account, client.trackCover(it.id)) }
        }.distinctBy { it.id }
    }
}
