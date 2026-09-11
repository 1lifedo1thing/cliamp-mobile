package stream.cliamp.mobile.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import stream.cliamp.mobile.data.db.CacheDao
import stream.cliamp.mobile.data.db.CliampDatabase
import stream.cliamp.mobile.data.db.EpisodeProgressEntity
import stream.cliamp.mobile.data.db.KvCacheEntity
import stream.cliamp.mobile.data.db.PodcastFeedCacheEntity
import stream.cliamp.mobile.data.db.toEntity
import stream.cliamp.mobile.net.Http

/** How the podcast directory is currently ordered or filtered. */
sealed interface PodcastQuery {
    /** An empty [country] is "all countries", resolved as Apple's global chart. */
    data class Top(val country: String = "") : PodcastQuery
    data class Search(val text: String) : PodcastQuery
    data class Category(val genre: PodcastGenre) : PodcastQuery

    val label: String
        get() = when (this) {
            is Top -> "top shows"
            is Search -> "\"$text\""
            is Category -> genre.name.lowercase()
        }
}

data class PodcastDirectoryState(
    val query: PodcastQuery = PodcastQuery.Top(),
    val shows: List<PodcastShow> = emptyList(),
    val loading: Boolean = false,
    val exhausted: Boolean = false,
    val error: String? = null,
)

/** One show's feed, as the episode list sees it. */
data class ShowState(
    val show: PodcastShow? = null,
    val episodes: List<PodcastEpisode> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Podcasts, held the way [Repository] holds radio: a small resident list you
 * own (subscriptions, where radio has cliamp's channels and favourites) beside
 * a large directory that is paged and never fully materialised.
 *
 * The paging differs, and it has to. Radio-browser takes an offset, so a page
 * is a request. Apple's search takes neither an offset nor more than a hundred
 * results, so a query is one request and a page is a slice of what came back;
 * the chart is the other way round, arriving as bare ids that need resolving,
 * so there a page is a [PodcastDirectory.lookup] of the next [PAGE] of them.
 * Every single chart is capped small, so once the global one is spent the
 * queue moves on to each genre's chart - the directory keeps loading the way
 * the radio one does, with Apple's famous shows up front. Either way the
 * screen sees the same growing list and the same exhausted flag.
 */
class PodcastRepository(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val db = CliampDatabase.get(context)
    private val dao = db.podcasts()
    private val cache: CacheDao = db.cache()

    private val _directory = MutableStateFlow(PodcastDirectoryState())
    val directory: StateFlow<PodcastDirectoryState> = _directory.asStateFlow()

    private val _show = MutableStateFlow(ShowState())
    val show: StateFlow<ShowState> = _show.asStateFlow()

    // Subscriptions come straight from Room: a toggle or a feed add writes the
    // row and the invalidation tracker re-emits the list, so the subscribing
    // sections track the table without an in-memory copy to keep in sync.
    val subscriptions: Flow<List<PodcastShow>> =
        dao.subscriptions().map { rows -> rows.map { it.toShow() } }

    /** Every saved position, keyed by episode URL, for badging a list at once. */
    val progress: Flow<Map<String, EpisodeProgress>> =
        dao.allProgress().map { rows -> rows.associate { it.url to it.toProgress() } }

    private val pageLock = Mutex()
    private val PAGE = 30

/** Chart ids not yet resolved to shows, in chart order. */
private var chartCursor: List<String> = emptyList()

    /** The next charts, fetched lazily as the current one runs out. Any single
     * chart is capped, so the directory pages past the global chart into each
     * genre's to keep going the way the radio directory does. Each entry is a
     * fetch of that chart's ids, resolved a page at a time on arrival. */
    private val chartQueue = mutableListOf<suspend () -> List<String>>()

    /** Search or category results fetched but not yet shown. */
    private var pending: List<PodcastShow> = emptyList()

    fun bootstrap() = load(PodcastQuery.Top(), reset = true)

    fun load(query: PodcastQuery, reset: Boolean) {
        scope.launch {
            pageLock.withLock {
                val cur = _directory.value
                if (!reset && (cur.loading || cur.exhausted || cur.error != null)) return@withLock
                // Same stale-while-revalidate as radio: a reset keeps the old
                // rows on screen (and their scroll position) instead of
                // flashing empty while the new query loads.
                _directory.value =
                    if (reset) PodcastDirectoryState(query = query, loading = true, shows = cur.shows)
                    else cur.copy(loading = true, error = null)

                if (reset) {
                    chartCursor = emptyList()
                    pending = emptyList()
                    chartQueue.clear()
                    _directory.value = PodcastDirectoryState(query = query, loading = true, shows = cur.shows)
                    // Fill the screen from the last snapshot of this query while
                    // the network answers; the first live page replaces it.
                    restore(query)
                    val primed = runCatching {
                        retryFetch {
                            when (query) {
                                is PodcastQuery.Top -> {
                                    chartCursor = PodcastDirectory.chartIds(country = query.country.ifEmpty { "us" })
                                    PodcastDirectory.genres.forEach { g ->
                                        chartQueue.add {
                                            PodcastDirectory.genreChartIds(query.country.ifEmpty { "us" }, g.id)
                                        }
                                    }
                                }
                                is PodcastQuery.Search -> pending = PodcastDirectory.search(query.text)
                                is PodcastQuery.Category -> pending = PodcastDirectory.byGenre(query.genre)
                            }
                        }
                    }
                    primed.exceptionOrNull()?.let { e ->
                        val cur = _directory.value
                        // A snapshot already on screen is better than an error;
                        // the failure can retry silently next visit.
                        _directory.value = if (cur.shows.isNotEmpty()) cur.copy(loading = false)
                        else PodcastDirectoryState(
                            query = query,
                            loading = false,
                            error = e.message ?: "directory unreachable",
                        )
                        return@withLock
                    }
                }

                val base = if (reset) emptyList() else _directory.value.shows
                val next = runCatching {
                    if (chartCursor.isNotEmpty() || chartQueue.isNotEmpty()) {
                        if (chartCursor.isEmpty()) {
                            // The current chart ran out; move on to the next
                            // (a genre chart) so the directory keeps going
                            // instead of quietly ending at Apple's cap.
                            chartCursor = retryFetch { chartQueue.removeAt(0)() }
                        }
                        val ids = chartCursor.take(PAGE)
                        val shows = retryFetch { PodcastDirectory.lookup(ids) }
                        // Only consume the ids once they have actually resolved:
                        // a page that fails (connection dropped, Apple rate
                        // limit) must be retried, not silently skipped.
                        chartCursor = chartCursor.drop(ids.size)
                        shows
                    } else {
                        val slice = pending.take(PAGE)
                        pending = pending.drop(slice.size)
                        slice
                    }
                }

                _directory.value = next.fold(
                    onSuccess = { list ->
                        val seen = base.mapTo(HashSet()) { it.feedUrl }
                        val merged = base + list.filter { seen.add(it.feedUrl) }
                        PodcastDirectoryState(
                            query = query,
                            shows = merged,
                            loading = false,
                            exhausted = chartCursor.isEmpty() && chartQueue.isEmpty() && pending.isEmpty(),
                        ).also { state ->
                            _directory.value = state
                            if (reset) snapshot(query, merged)
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

    fun nextPage() = load(_directory.value.query, reset = false)

    /** The last first-page snapshot of [query], or nothing the first time. */
    private suspend fun restore(query: PodcastQuery) {
        val row = cache.get(keyOf(query)) ?: return
        val cached = runCatching { Http.json.decodeFromString<List<PodcastShow>>(row.json) }.getOrNull() ?: return
        val cur = _directory.value
        // Only a page that is still waiting counts; a live list already
        // fetched (or another query) wins. Loading stays true so the footer
        // keeps saying "loading more…" until the fresh page lands.
        if (cur.query == query && cur.shows.isEmpty()) {
            _directory.value = cur.copy(shows = cached)
        }
    }

    private fun snapshot(query: PodcastQuery, shows: List<PodcastShow>) {
        scope.launch {
            runCatching {
                cache.put(
                    KvCacheEntity(
                        key = keyOf(query),
                        json = Http.json.encodeToString(shows),
                        savedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    private fun keyOf(query: PodcastQuery): String = when (query) {
        is PodcastQuery.Top -> "podcasts:top:${query.country.ifEmpty { "all" }.trim().lowercase()}"
        is PodcastQuery.Search -> "podcasts:search:${query.text.trim().lowercase()}"
        is PodcastQuery.Category -> "podcasts:cat:${query.genre.id}"
    }

    /**
     * Loads [show]'s feed. The show is published before the fetch so the
     * episode screen can draw its header and artwork immediately, the same way
     * a tapped station is published before its stream resolves. A feed
     * snapshot from the last visit fills the list while the network answers, so
     * re-opening a show is instant instead of another multi-megabyte download.
     */
    fun openShow(show: PodcastShow) {
        val held = _show.value
        // Re-entering a show that is already in hand should be instant, so a
        // second tap is not a second 4 MB download. [refreshShow] is how a
        // re-read is asked for.
        if (held.show?.feedUrl == show.feedUrl && held.episodes.isNotEmpty() && !held.loading) return
        _show.value = ShowState(show = show, loading = true)
        scope.launch {
            // Fill the screen from this feed's last snapshot while the fresh
            // one downloads. Loading stays true so "reading the feed…" shows
            // until the new list lands.
            restoreFeed(show.feedUrl)
            val loaded = retryFetch { PodcastFeed.load(show) }.getOrElse { e ->
                val cur = _show.value
                // A snapshot already on screen is better than an error; the
                // failure can retry silently next visit.
                _show.value = if (cur.episodes.isNotEmpty()) cur.copy(loading = false)
                else ShowState(show = show, loading = false, error = e.message ?: "feed unreachable")
                return@launch
            }
            _show.value = ShowState(show = loaded.show, episodes = loaded.episodes)
            snapshotFeed(loaded.show, loaded.episodes)
            // A subscription keeps whatever the feed knows that the
            // directory did not, so the list stops looking half-filled.
            if (dao.isSubscribed(loaded.show.feedUrl)) {
                dao.subscribe(loaded.show.toEntity(dao.nextTopPosition() + 1))
            }
        }
    }

    /** The last snapshot of [feedUrl]'s feed, or nothing when it has gone stale.
     * The caller refreshes regardless, so freshness only decides whether the
     * cached list is worth a first paint (a feed a month old still beats a
     * spinner, but why re-fetch it every open). */
    private suspend fun restoreFeed(feedUrl: String) {
        val row = cache.getFeed(feedUrl) ?: return
        if (System.currentTimeMillis() - row.savedAt > FEED_TTL) return
        val cached = runCatching {
            val show = Http.json.decodeFromString<PodcastShow>(row.showJson)
            val episodes = Http.json.decodeFromString<List<PodcastEpisode>>(row.episodesJson)
            show to episodes
        }.getOrNull() ?: return
        val cur = _show.value
        // Only a feed that is still waiting counts; an already-live list
        // (or another show) wins.
        if (cur.show?.feedUrl == feedUrl && cur.episodes.isEmpty()) {
            _show.value = cur.copy(show = cached.first, episodes = cached.second)
        }
    }

    private fun snapshotFeed(show: PodcastShow, episodes: List<PodcastEpisode>) {
        episodeIndex = null
        scope.launch {
            runCatching {
                cache.putFeed(
                    PodcastFeedCacheEntity(
                        feedUrl = show.feedUrl,
                        showJson = Http.json.encodeToString(show),
                        episodesJson = Http.json.encodeToString(episodes),
                        savedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    fun refreshShow() = _show.value.show?.let { openShow(it) }

    fun closeShow() { _show.value = ShowState() }

    suspend fun isSubscribed(feedUrl: String): Boolean = dao.isSubscribed(feedUrl)

    /** Returns the new state, so a row can toggle without re-reading. */
    suspend fun toggleSubscription(show: PodcastShow): Boolean {
        episodeIndex = null
        if (dao.isSubscribed(show.feedUrl)) {
            dao.unsubscribe(show.feedUrl)
            return false
        }
        dao.subscribe(show.toEntity(dao.nextTopPosition()))
        return true
    }

    /** A feed URL typed by hand, resolved through Apple where it is listed. */
    suspend fun addFeed(url: String): PodcastShow? {
        val show = PodcastDirectory.byFeedUrl(url) ?: return null
        dao.subscribe(show.toEntity(dao.nextTopPosition()))
        return show
    }

    suspend fun progressFor(url: String): EpisodeProgress? = dao.progress(url)?.toProgress()

    /**
     * Every cached episode of every subscribed show, as (show, episode)
     * pairs. Feeds snapshot on open, so this is the offline episode index
     * search reads: flattened once, then held in memory until a snapshot or
     * a subscription changes it. A show never opened contributes nothing -
     * global episode search needs a server index that does not exist.
     */
    @Volatile private var episodeIndex: List<Pair<PodcastShow, PodcastEpisode>>? = null

    suspend fun subscribedEpisodes(): List<Pair<PodcastShow, PodcastEpisode>> {
        episodeIndex?.let { return it }
        val subs = subscriptions.first()
        if (subs.isEmpty()) return emptyList()
        val out = ArrayList<Pair<PodcastShow, PodcastEpisode>>()
        for (show in subs) {
            val row = cache.getFeed(show.feedUrl) ?: continue
            val episodes = runCatching {
                Http.json.decodeFromString<List<PodcastEpisode>>(row.episodesJson)
            }.getOrDefault(emptyList())
            if (episodes.isEmpty()) continue
            val liveShow = runCatching {
                Http.json.decodeFromString<PodcastShow>(row.showJson)
            }.getOrDefault(show)
            episodes.mapTo(out) { liveShow to it }
        }
        episodeIndex = out
        return out
    }

    /**
     * Where playback should start. Anything within [NEAR_END] of the end counts
     * as finished and restarts from zero, so a completed episode replayed does
     * not open two seconds from its own credits. Keyed by URL, so local and
     * provider tracks resume exactly like episodes; live radio is not a track
     * and always starts at zero.
     */
    suspend fun resumePosition(station: Station): Long {
        if (!station.isTrack) return 0L
        val p = dao.progress(station.url) ?: return 0L
        if (p.completed) return 0L
        if (p.durationMs > 0 && p.positionMs >= p.durationMs - NEAR_END) return 0L
        return p.positionMs.coerceAtLeast(0L)
    }

    suspend fun saveProgress(station: Station, positionMs: Long, durationMs: Long) {
        if (!station.isTrack) return
        if (positionMs <= 0) return
        val done = durationMs > 0 && positionMs >= durationMs - NEAR_END
        dao.saveProgress(
            EpisodeProgressEntity(
                url = station.url,
                positionMs = positionMs,
                durationMs = durationMs,
                completed = done,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun markCompleted(station: Station) {
        dao.saveProgress(
            EpisodeProgressEntity(
                url = station.url,
                positionMs = station.durationMs,
                durationMs = station.durationMs,
                completed = true,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun clearProgress(station: Station) = dao.clearProgress(station.url)

    private companion object {
        /** Close enough to the end to call it listened. */
        const val NEAR_END = 30_000L

        /** How long a cached feed is worth showing while it refreshes. */
        const val FEED_TTL = 12L * 60 * 60 * 1000
    }
}
