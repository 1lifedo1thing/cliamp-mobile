package stream.cliamp.mobile.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How the directory list is currently ordered or filtered. */
sealed interface DirectoryQuery {
    data object TopVoted : DirectoryQuery
    data object Trending : DirectoryQuery
    data class Search(val text: String) : DirectoryQuery
    data class Tag(val tag: String) : DirectoryQuery
    data class Country(val code: String, val countryName: String) : DirectoryQuery

    val label: String
        get() = when (this) {
            TopVoted -> "top voted"
            Trending -> "trending"
            is Search -> "\"$text\""
            is Tag -> "#$tag"
            is Country -> countryName.lowercase()
        }
}

data class DirectoryState(
    val query: DirectoryQuery = DirectoryQuery.TopVoted,
    val stations: List<Station> = emptyList(),
    val loading: Boolean = false,
    val exhausted: Boolean = false,
    val error: String? = null,
)

/**
 * Holds the two catalogues side by side: cliamp's own dozen channels, which are
 * small enough to keep resident, and the directory, which is paged in 60 at a
 * time and never fully materialised.
 */
class Repository(
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val _cliamp = MutableStateFlow(CliampRadio.builtin)
    val cliamp: StateFlow<List<Station>> = _cliamp.asStateFlow()

    private val _directory = MutableStateFlow(DirectoryState())
    val directory: StateFlow<DirectoryState> = _directory.asStateFlow()

    private val _directoryStats = MutableStateFlow<DirectoryStats?>(null)
    val directoryStats: StateFlow<DirectoryStats?> = _directoryStats.asStateFlow()

    private val _tags = MutableStateFlow<List<NameCount>>(emptyList())
    val tags: StateFlow<List<NameCount>> = _tags.asStateFlow()

    private val _countries = MutableStateFlow<List<CountryCount>>(emptyList())
    val countries: StateFlow<List<CountryCount>> = _countries.asStateFlow()

    private val pageLock = Mutex()
    private val pageSize = 60

    fun bootstrap() {
        scope.launch { _cliamp.value = CliampRadio.fetchStations() }
        scope.launch { _directoryStats.value = RadioBrowser.stats() }
        scope.launch { _tags.value = RadioBrowser.topTags(60) }
        scope.launch { _countries.value = RadioBrowser.topCountries() }
        loadDirectory(DirectoryQuery.TopVoted, reset = true)
    }

    fun loadDirectory(query: DirectoryQuery, reset: Boolean) {
        scope.launch {
            pageLock.withLock {
                val cur = _directory.value
                if (!reset && (cur.loading || cur.exhausted)) return@withLock
                val offset = if (reset) 0 else cur.stations.size
                _directory.value =
                    if (reset) DirectoryState(query = query, loading = true)
                    else cur.copy(loading = true, error = null)

                val page = runCatching {
                    when (query) {
                        DirectoryQuery.TopVoted -> RadioBrowser.topVoted(offset, pageSize)
                        DirectoryQuery.Trending -> RadioBrowser.trending(offset, pageSize)
                        is DirectoryQuery.Search -> RadioBrowser.searchByName(query.text, offset, pageSize)
                        is DirectoryQuery.Tag -> RadioBrowser.byTag(query.tag, offset, pageSize)
                        is DirectoryQuery.Country -> RadioBrowser.byCountryCode(query.code, offset, pageSize)
                    }
                }

                _directory.value = page.fold(
                    onSuccess = { list ->
                        val base = if (reset) emptyList() else _directory.value.stations
                        val seen = base.mapTo(HashSet()) { it.url }
                        val merged = base + list.filter { seen.add(it.url) }
                        DirectoryState(
                            query = query,
                            stations = merged,
                            loading = false,
                            // Only an empty page is the end. A short page is
                            // usually a slow server trimming the answer, not the
                            // last of the catalogue, and calling it exhausted
                            // froze the directory mid-list.
                            exhausted = list.isEmpty(),
                        )
                    },
                    onFailure = { e ->
                        _directory.value.copy(
                            loading = false,
                            error = e.message ?: "directory unreachable",
                        )
                    },
                )
            }
        }
    }

    fun nextPage() = loadDirectory(_directory.value.query, reset = false)

    fun reportPlay(station: Station) {
        if (station.source == StationSource.Directory) {
            scope.launch { RadioBrowser.reportClick(station.uuid) }
        }
        scope.launch { prefs.pushHistory(station) }
        scope.launch { prefs.setLastStation(station) }
    }

}
