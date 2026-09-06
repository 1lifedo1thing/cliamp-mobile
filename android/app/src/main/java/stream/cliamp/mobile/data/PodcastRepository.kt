package stream.cliamp.mobile.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import stream.cliamp.mobile.data.db.CliampDatabase
import stream.cliamp.mobile.data.db.EpisodeProgressEntity
import stream.cliamp.mobile.data.db.toEntity

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
 * Either way the screen sees the same growing list and the same exhausted flag.
 */
class PodcastRepository(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val db = CliampDatabase.get(context)
    private val dao = db.podcasts()

    private val _directory = MutableStateFlow(PodcastDirectoryState())
    val directory: StateFlow<PodcastDirectoryState> = _directory.asStateFlow()

    private val _show = MutableStateFlow(ShowState())
    val show: StateFlow<ShowState> = _show.asStateFlow()

    // Subscriptions and the continue list are mirrored in memory so a toggle or
    // a progress write shows up on screen instantly, without waiting for the
    // Room round-trip (write → invalidation → re-query → emit) that used to
    // lag every change and then jump the grid.
    private val _subscriptions = MutableStateFlow<List<PodcastShow>>(emptyList())
    val subscriptions: StateFlow<List<PodcastShow>> = _subscriptions.asStateFlow()

    /** True once the subscription mirror has been seeded from disk. The list
     * renders skeletons until this flips, so the subscribed section does not
     * jump between "empty" and "loaded" on first draw. */
    private val _subscriptionsReady = MutableStateFlow(false)
    val subscriptionsReady: StateFlow<Boolean> = _subscriptionsReady.asStateFlow()

    /** Started and unfinished episodes, newest first. */
    private val _continueListening = MutableStateFlow<List<Station>>(emptyList())
    val continueListening: StateFlow<List<Station>> = _continueListening.asStateFlow()

    /** Every saved position, keyed by episode URL, for badging a list at once. */
    val progress: Flow<Map<String, EpisodeProgress>> =
        dao.allProgress().map { rows -> rows.associate { it.url to it.toProgress() } }

    private val pageLock = Mutex()
    private val PAGE = 30

    /** Chart ids not yet resolved to shows, in chart order. */
    private var chartCursor: List<String> = emptyList()

    /** Search or category results fetched but not yet shown. */
    private var pending: List<PodcastShow> = emptyList()

    fun bootstrap() {
        // Seed the mirrors synchronously before any screen can compose, so the
        // subscribed section is ready on the very first frame. There is then no
        // loading window, so no placeholder is ever needed and nothing shifts.
        runBlocking(Dispatchers.IO) {
            _subscriptions.value = dao.readSubscriptions().map { it.toShow() }
            _continueListening.value = dao.continueListening().first().map { it.toStation() }
        }
        _subscriptionsReady.value = true
        load(PodcastQuery.Top(), reset = true)
    }

    fun load(query: PodcastQuery, reset: Boolean) {
        scope.launch {
            pageLock.withLock {
                val cur = _directory.value
                if (!reset && (cur.loading || cur.exhausted)) return@withLock
                _directory.value =
                    if (reset) PodcastDirectoryState(query = query, loading = true)
                    else cur.copy(loading = true, error = null)

                if (reset) {
                    chartCursor = emptyList()
                    pending = emptyList()
                    val primed = runCatching {
                        when (query) {
                            is PodcastQuery.Top -> chartCursor = PodcastDirectory.chartIds(country = query.country.ifEmpty { "us" }, limit = 100)
                            is PodcastQuery.Search -> pending = PodcastDirectory.search(query.text)
                            is PodcastQuery.Category -> pending = PodcastDirectory.byGenre(query.genre)
                        }
                    }
                    primed.exceptionOrNull()?.let { e ->
                        _directory.value = PodcastDirectoryState(
                            query = query,
                            loading = false,
                            error = e.message ?: "directory unreachable",
                        )
                        return@withLock
                    }
                }

                val base = if (reset) emptyList() else _directory.value.shows
                val next = runCatching {
                    if (chartCursor.isNotEmpty()) {
                        val ids = chartCursor.take(PAGE)
                        chartCursor = chartCursor.drop(ids.size)
                        PodcastDirectory.lookup(ids)
                    } else {
                        val slice = pending.take(PAGE)
                        pending = pending.drop(slice.size)
                        slice
                    }
                }

                _directory.value = next.fold(
                    onSuccess = { list ->
                        val seen = base.mapTo(HashSet()) { it.feedUrl }
                        PodcastDirectoryState(
                            query = query,
                            shows = base + list.filter { seen.add(it.feedUrl) },
                            loading = false,
                            exhausted = chartCursor.isEmpty() && pending.isEmpty(),
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

    fun nextPage() = load(_directory.value.query, reset = false)

    /**
     * Loads [show]'s feed. The show is published before the fetch so the
     * episode screen can draw its header and artwork immediately, the same way
     * a tapped station is published before its stream resolves.
     */
    fun openShow(show: PodcastShow) {
        val held = _show.value
        // Re-entering a show that is already in hand should be instant, so a
        // second tap is not a second 4 MB download. [refreshShow] is how a
        // re-read is asked for.
        if (held.show?.feedUrl == show.feedUrl && held.episodes.isNotEmpty() && !held.loading) return
        _show.value = ShowState(show = show, loading = true)
        scope.launch {
            PodcastFeed.load(show).fold(
                onSuccess = { loaded ->
                    _show.value = ShowState(show = loaded.show, episodes = loaded.episodes)
                    // A subscription keeps whatever the feed knows that the
                    // directory did not, so the list stops looking half-filled.
                    if (dao.isSubscribed(loaded.show.feedUrl)) {
                        dao.subscribe(loaded.show.toEntity(dao.nextTopPosition() + 1))
                    }
                },
                onFailure = { e ->
                    _show.value = ShowState(
                        show = show,
                        loading = false,
                        error = e.message ?: "feed unreachable",
                    )
                },
            )
        }
    }

    fun refreshShow() = _show.value.show?.let { openShow(it) }

    fun closeShow() { _show.value = ShowState() }

    suspend fun isSubscribed(feedUrl: String): Boolean = dao.isSubscribed(feedUrl)

    /** Returns the new state, so a row can toggle without re-reading. */
    suspend fun toggleSubscription(show: PodcastShow): Boolean {
        val nowSubscribed = !dao.isSubscribed(show.feedUrl)
        // Update the in-memory mirror first so the section reflects the tap on
        // the very next frame, before the disk write lands. New shows land on
        // top, matching the DAO's position ordering.
        _subscriptions.value = if (nowSubscribed) {
            listOf(show) + _subscriptions.value.filterNot { it.feedUrl == show.feedUrl }
        } else {
            _subscriptions.value.filterNot { it.feedUrl == show.feedUrl }
        }
        if (nowSubscribed) {
            dao.subscribe(show.toEntity(dao.nextTopPosition()))
        } else {
            dao.unsubscribe(show.feedUrl)
        }
        return nowSubscribed
    }

    /** A feed URL typed by hand, resolved through Apple where it is listed. */
    suspend fun addFeed(url: String): PodcastShow? {
        val show = PodcastDirectory.byFeedUrl(url) ?: return null
        // Optimistic mirror update first; the disk write follows.
        _subscriptions.value = listOf(show) + _subscriptions.value.filterNot { it.feedUrl == show.feedUrl }
        dao.subscribe(show.toEntity(dao.nextTopPosition()))
        return show
    }

    suspend fun progressFor(url: String): EpisodeProgress? = dao.progress(url)?.toProgress()

    /**
     * Where playback should start. Anything within [NEAR_END] of the end counts
     * as finished and restarts from zero, so a completed episode replayed does
     * not open two seconds from its own credits.
     */
    suspend fun resumePosition(station: Station): Long {
        if (station.source != StationSource.Podcast) return 0L
        val p = dao.progress(station.url) ?: return 0L
        if (p.completed) return 0L
        if (p.durationMs > 0 && p.positionMs >= p.durationMs - NEAR_END) return 0L
        return p.positionMs.coerceAtLeast(0L)
    }

    suspend fun saveProgress(station: Station, positionMs: Long, durationMs: Long) {
        if (station.source != StationSource.Podcast) return
        if (positionMs <= 0) return
        val done = durationMs > 0 && positionMs >= durationMs - NEAR_END
        // Mirror the continue-list query into memory (started, not finished,
        // newest first) before touching disk, so the row lands on the next
        // frame rather than waiting on the DB write.
        _continueListening.value = if (done) {
            _continueListening.value.filterNot { it.url == station.url }
        } else if (positionMs > 30_000L) {
            listOf(station) + _continueListening.value.filterNot { it.url == station.url }
        } else {
            _continueListening.value
        }
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
        _continueListening.value = _continueListening.value.filterNot { it.url == station.url }
    }

    suspend fun clearProgress(station: Station) = dao.clearProgress(station.url).also {
        _continueListening.value = _continueListening.value.filterNot { it.url == station.url }
    }

    private companion object {
        /** Close enough to the end to call it listened. */
        const val NEAR_END = 30_000L
    }
}
