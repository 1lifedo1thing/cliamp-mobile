package stream.kleeamp.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.data.DirectoryQuery
import stream.kleeamp.mobile.data.DirectoryState
import stream.kleeamp.mobile.data.LocalLibrary
import stream.kleeamp.mobile.data.NameCount
import stream.kleeamp.mobile.data.PodcastDirectory
import stream.kleeamp.mobile.data.PodcastEpisode
import stream.kleeamp.mobile.data.PodcastRepository
import stream.kleeamp.mobile.data.PodcastShow
import stream.kleeamp.mobile.data.Prefs
import stream.kleeamp.mobile.data.Repository
import stream.kleeamp.mobile.data.Station
import stream.kleeamp.mobile.data.provider.ProviderAccount
import stream.kleeamp.mobile.data.provider.ProviderStore
import stream.kleeamp.mobile.ui.search.GlobalSearch
import stream.kleeamp.mobile.ui.search.SearchHit

enum class SearchScope(val label: String) {
    All("all"), Media("local"), Radio("radio"), Pods("podcasts"),
    Tags("tags"), Providers("providers"),
}

class SearchViewModel(
    private val repository: Repository,
    private val podcasts: PodcastRepository,
    private val prefs: Prefs,
    private val localLibrary: LocalLibrary,
    private val providers: ProviderStore,
) : ViewModel() {
    data class UiState(
        val filter: SearchScope = SearchScope.All,
        val term: String = "",
        val results: List<SearchHit> = emptyList(),
        val shown: List<SearchHit> = emptyList(),
    )

    sealed interface Event {
        data class QueryChanged(val query: String) : Event
        data class FilterChanged(val filter: SearchScope) : Event
        data class Submitted(val raw: String) : Event
    }

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(SearchScope.All)
    // Podcast search hits, kept local to this VM. The Podcasts tab shares
    // the same PodcastRepository, so routing search through podcasts.load()
    // left the tab stuck on the last search query instead of its own
    // top/category directory. The search still resolves shows by name, but
    // through a query-scoped fetch below rather than a rewrite of the shared
    // state - podcasts.load()/browse state is never touched here.
    private val podcastHits = MutableStateFlow<List<PodcastShow>>(emptyList())
    // Cached episodes of subscribed shows, for the episode tail of results.
    // Resolved in the same debounced fetch as the show directory above.
    private val episodeIndex = MutableStateFlow<List<Pair<PodcastShow, PodcastEpisode>>>(emptyList())

    /** Query, scope filter and the radio catalogues the query runs against. */
    private data class DirectoryInputs(
        val term: String = "",
        val filter: SearchScope = SearchScope.All,
        val directory: DirectoryState = DirectoryState(),
        val cliamp: List<Station> = emptyList(),
        val tags: List<NameCount> = emptyList(),
    )

    /** Resident collections: local songs, favourites, providers, subscriptions. */
    private data class LibraryInputs(
        val songs: List<Station> = emptyList(),
        val favorites: List<Station> = emptyList(),
        val recent: List<Station> = emptyList(),
        val providerAccounts: List<ProviderAccount> = emptyList(),
        val subscriptions: List<PodcastShow> = emptyList(),
    )

    val state: StateFlow<UiState> = combine(
        combine(
            query.map { it.trim() }.distinctUntilChanged(),
            filter,
            repository.directory,
            repository.cliamp,
            repository.tags,
            ::DirectoryInputs,
        ),
        combine(
            localLibrary.songs,
            prefs.favorites,
            prefs.history,
            providers.accounts,
            podcasts.subscriptions,
            ::LibraryInputs,
        ),
        podcastHits,
        episodeIndex,
    ) { dir, lib, podcastHits, episodeIndex ->
        val radio = dir.cliamp + dir.directory.stations
        // Subscriptions are resident, the search half is whatever the debounced
        // query just fetched, so a show can be found whether or not it is
        // already followed. podcastHits is this screen's own copy: the Podcasts
        // tab reads the same repository but must keep its own top/category browse.
        val shows = lib.subscriptions + podcastHits
        val subscribedFeeds = lib.subscriptions.mapTo(HashSet()) { it.feedUrl }
        val results = GlobalSearch.run(
            dir.term, lib.songs, lib.favorites, lib.recent, radio, dir.tags,
            lib.providerAccounts, shows, subscribedFeeds, episodeIndex,
        ).hits
        val shown = when (dir.filter) {
            SearchScope.All -> results
            // LOCAL means on-device files only: favourited or recently played
            // radio streams file under radio, never here.
            SearchScope.Media -> results.filter { it is SearchHit.Song }
            SearchScope.Radio -> results.filter { it is SearchHit.StationHit }
            SearchScope.Pods -> results.filter { it is SearchHit.Show || it is SearchHit.Episode }
            SearchScope.Tags -> results.filter { it is SearchHit.Tag }
            SearchScope.Providers -> results.filter { it is SearchHit.Provider }
        }
        UiState(filter = dir.filter, term = dir.term, results = results, shown = shown)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        // Debounce the directory: it is somebody else's server, not ours. The local
        // and radio fuzzy pass above runs instantly on what we already hold.
        viewModelScope.launch {
            query.map { it.trim() }.distinctUntilChanged().collectLatest { term ->
                if (term.length < 2) {
                    episodeIndex.value = emptyList()
                    return@collectLatest
                }
                delay(320)
                repository.loadDirectory(DirectoryQuery.Search(term), reset = true)
                // The podcast directory takes the same query, so a show can be found
                // by name - through PodcastDirectory.search, never podcasts.load(),
                // which would rewrite the shared directory state the Podcasts tab
                // reads. Unlike the Stations tab, the Podcasts tab keeps the search
                // rather than resetting it: Apple's search returns whole shows in one
                // request with nothing to page, so the searched list IS the directory
                // for as long as the query stands, and its header names the query.
                podcastHits.value = runCatching { PodcastDirectory.search(term) }.getOrDefault(emptyList())
                episodeIndex.value = runCatching { podcasts.subscribedEpisodes() }.getOrDefault(emptyList())
            }
        }
    }

    fun onEvent(e: Event) {
        when (e) {
            is Event.QueryChanged -> query.value = e.query
            is Event.FilterChanged -> filter.value = e.filter
            is Event.Submitted -> {
                val text = e.raw.trim()
                if (text.isEmpty()) return
                repository.loadDirectory(DirectoryQuery.Search(text), reset = true)
            }
        }
    }
}
