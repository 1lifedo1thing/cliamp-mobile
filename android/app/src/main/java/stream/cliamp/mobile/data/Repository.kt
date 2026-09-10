package stream.cliamp.mobile.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import stream.cliamp.mobile.data.db.CliampDatabase
import stream.cliamp.mobile.data.db.CacheDao
import stream.cliamp.mobile.data.db.KvCacheEntity
import stream.cliamp.mobile.net.Http

/** How the directory list is currently ordered or filtered. */
sealed interface DirectoryQuery {
    data object TopVoted : DirectoryQuery
    data object Trending : DirectoryQuery
    data class Search(val text: String) : DirectoryQuery
    data class Tag(val tag: String) : DirectoryQuery
    data class Country(val code: String, val countryName: String) : DirectoryQuery

    val label: String
        get() = when (this) {
            TopVoted -> "top"
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
    context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val cache: CacheDao = CliampDatabase.get(context).cache()

    private val _cliamp = MutableStateFlow(CliampRadio.builtin)
    val cliamp: StateFlow<List<Station>> = _cliamp.asStateFlow()

    private val _cliampError = MutableStateFlow<String?>(null)
    val cliampError: StateFlow<String?> = _cliampError.asStateFlow()

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
        refreshCliamp()
        // Meta first: instant chips and stats from the last snapshot, then the
        // live fetches replace them. Same snapshot pattern as the directories.
        scope.launch { restoreMeta() }
        scope.launch {
            _directoryStats.value = retryFetch { RadioBrowser.stats() }
            _directoryStats.value?.let { snapshotMeta("meta:stats", it) }
        }
        scope.launch {
            _tags.value = retryFetch { RadioBrowser.topTags(60) }
            _tags.value.takeIf { it.isNotEmpty() }?.let { snapshotMeta("meta:tags", it) }
        }
        scope.launch {
            _countries.value = retryFetch { RadioBrowser.topCountries() }
            _countries.value.takeIf { it.isNotEmpty() }?.let { snapshotMeta("meta:countries", it) }
        }
        loadDirectory(DirectoryQuery.TopVoted, reset = true)
    }

    fun refreshCliamp() {
        scope.launch {
            // cliamp is its own service; a silent drop used to abort this
            // coroutine uncaught (the loop crashed the app) or fell back to
            // builtins with no explanation. Keep the last good list on screen
            // and let the section say what happened instead.
            _cliampError.value = null
            runCatching { retryFetch { CliampRadio.fetchStations() } }
                .onSuccess {
                    _cliamp.value = it
                    prefetchCovers(it)
                }
                .onFailure { _cliampError.value = it.message ?: "cliamp radio unreachable" }
        }
    }

    fun loadDirectory(query: DirectoryQuery, reset: Boolean) {
        scope.launch {
            pageLock.withLock {
                val cur = _directory.value
                if (!reset && (cur.loading || cur.exhausted || cur.error != null)) return@withLock
                val offset = if (reset) 0 else cur.stations.size
                // A reset never empties what is on screen: the old rows stay
                // put (and keep their scroll position) while the new query
                // loads, then the live page replaces them. Emptying first is
                // what made every filter tap flash and jump.
                _directory.value =
                    if (reset) DirectoryState(query = query, loading = true, stations = cur.stations)
                    else cur.copy(loading = true, error = null)

                // A reset empties nothing the user already has on screen: fill
                // the list from the last snapshot of this query before the
                // network answers, then let the live page replace it.
                if (reset) restore(query)

                val page = runCatching {
                    retryFetch {
                        when (query) {
                            DirectoryQuery.TopVoted -> RadioBrowser.topVoted(offset, pageSize)
                            DirectoryQuery.Trending -> RadioBrowser.trending(offset, pageSize)
                            is DirectoryQuery.Search -> RadioBrowser.searchByName(query.text, offset, pageSize)
                            is DirectoryQuery.Tag -> RadioBrowser.byTag(query.tag, offset, pageSize)
                            is DirectoryQuery.Country -> RadioBrowser.byCountryCode(query.code, offset, pageSize)
                        }
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
                        ).also { state ->
                            _directory.value = state
                            // A first page is the whole "open the tab" moment;
                            // remember it so a cold start can render it instantly.
                            if (reset) snapshot(query, merged)
                            // Warm art for the first screenful while it is on
                            // screen: rows then hit memory/disk instead of
                            // queueing fresh fetches behind each other.
                            if (reset) prefetchCovers(merged)
                        }
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

    /** The last first-page snapshot of [query], or nothing the first time. */
    private suspend fun restore(query: DirectoryQuery) {
        val row = cache.get(keyOf(query)) ?: return
        val cached = runCatching { Http.json.decodeFromString<List<Station>>(row.json) }.getOrNull() ?: return
        val cur = _directory.value
        // Only a page that is still waiting counts. When a fetch already
        // landed (or the user moved on), the live list wins over a snapshot;
        // loading stays true so the footer still says "loading more…".
        if (cur.query == query && cur.stations.isEmpty()) {
            _directory.value = cur.copy(stations = cached)
        }
    }

    /**
     * Warms small art for the first screenful once a list lands, so rows hit
     * memory/disk instead of queueing fresh fetches behind each other on a
     * cold list. Fire-and-forget on the shared pool; a row that beats its
     * prefetch just does the same fetch it would have anyway.
     */
    private fun prefetchCovers(stations: List<Station>) {
        val head = stations.take(24)
        if (head.isEmpty()) return
        scope.launch {
            runCatching {
                head.map { async { StationArtSource.bitmapForSmall(it) } }.awaitAll()
            }
        }
    }

    private fun snapshot(query: DirectoryQuery, stations: List<Station>) {
        scope.launch {
            runCatching {
                cache.put(
                    KvCacheEntity(
                        key = keyOf(query),
                        json = Http.json.encodeToString(stations),
                        savedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    /** Last-good chips and stats, so cold tabs render before the network. */
    private suspend fun restoreMeta() {
        cache.get("meta:stats")?.let { row ->
            runCatching { Http.json.decodeFromString<DirectoryStats>(row.json) }.getOrNull()
                ?.let { if (_directoryStats.value == null) _directoryStats.value = it }
        }
        cache.get("meta:tags")?.let { row ->
            runCatching { Http.json.decodeFromString<List<NameCount>>(row.json) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { if (_tags.value.isEmpty()) _tags.value = it }
        }
        cache.get("meta:countries")?.let { row ->
            runCatching { Http.json.decodeFromString<List<CountryCount>>(row.json) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { if (_countries.value.isEmpty()) _countries.value = it }
        }
    }

    private inline fun <reified T> snapshotMeta(key: String, value: T) {
        scope.launch {
            runCatching {
                cache.put(
                    KvCacheEntity(
                        key = key,
                        json = Http.json.encodeToString(value),
                        savedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    private fun keyOf(query: DirectoryQuery): String = when (query) {
        DirectoryQuery.TopVoted -> "stations:top"
        DirectoryQuery.Trending -> "stations:trending"
        is DirectoryQuery.Search -> "stations:search:${query.text.trim().lowercase()}"
        is DirectoryQuery.Tag -> "stations:tag:${query.tag.trim().lowercase()}"
        is DirectoryQuery.Country -> "stations:country:${query.code.trim().lowercase()}"
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
