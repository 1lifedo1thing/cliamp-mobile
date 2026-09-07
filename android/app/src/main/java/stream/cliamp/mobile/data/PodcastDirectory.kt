package stream.cliamp.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import stream.cliamp.mobile.net.Http
import java.net.URLEncoder

/** One of Apple's podcast categories. The ids are Apple's own. */
data class PodcastGenre(val id: Int, val name: String)

/**
 * Apple's podcast directory, which is the radio-browser of podcasts: open, no
 * key, no account.
 *
 * Three things about it shape everything below, all three verified against the
 * live service rather than the docs:
 *
 *  1. Search has no offset. `&offset=20` returns the same first result as
 *     `&offset=0`, so a query is one request and the list is paged in the UI,
 *     not over the wire. [SEARCH_LIMIT] is where it actually stops - the docs
 *     claim 200, the service returns 100.
 *  2. The charts feed gives ids, names and artwork but no feed URL, so a chart
 *     is useless on its own. [lookup] takes comma-separated ids, so one extra
 *     request resolves a whole page of them. The feed generator stops at 200,
 *     so [CHART_LIMIT] really is the whole chart; the /api/v2 feed returns
 *     only the first hundred and ignores an offset, so it can never page.
 *  3. `genreId` filters but does not browse: on its own it returns nothing, so
 *     a category browse is the genre's own name as the search term with the id
 *     narrowing it.
 *
 * Apple rate-limits this at roughly twenty calls a minute per address, which is
 * why nothing here polls and why chart feeds are resolved a page at a time.
 */
object PodcastDirectory {

    private const val ITUNES = "https://itunes.apple.com"

    /** What a search actually returns at most, whatever limit is asked for. */
    const val SEARCH_LIMIT = 100

    /** The whole top chart, in rank order. The feed generator refuses more. */
    const val CHART_LIMIT = 200

    val genres: List<PodcastGenre> = listOf(
        PodcastGenre(1489, "News"),
        PodcastGenre(1318, "Technology"),
        PodcastGenre(1488, "True Crime"),
        PodcastGenre(1303, "Comedy"),
        PodcastGenre(1324, "Society & Culture"),
        PodcastGenre(1487, "History"),
        PodcastGenre(1533, "Science"),
        PodcastGenre(1512, "Health & Fitness"),
        PodcastGenre(1321, "Business"),
        PodcastGenre(1310, "Music"),
        PodcastGenre(1545, "Sports"),
        PodcastGenre(1301, "Arts"),
        PodcastGenre(1304, "Education"),
        PodcastGenre(1483, "Fiction"),
        PodcastGenre(1309, "TV & Film"),
        PodcastGenre(1314, "Religion & Spirituality"),
        PodcastGenre(1305, "Kids & Family"),
        PodcastGenre(1502, "Leisure"),
        PodcastGenre(1511, "Government"),
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * One request, [SEARCH_LIMIT] shows at most. [genreId] narrows an already
     * termed search; it cannot stand in for one.
     */
    suspend fun search(term: String, genreId: Int = 0): List<PodcastShow> {
        if (term.isBlank()) return emptyList()
        val genre = if (genreId > 0) "&genreId=$genreId" else ""
        val body = Http.text("$ITUNES/search?media=podcast&entity=podcast&term=${enc(term)}$genre&limit=$SEARCH_LIMIT")
        return decode(body)
    }

    /** A category browse: the genre's own name, narrowed by its id. */
    suspend fun byGenre(genre: PodcastGenre): List<PodcastShow> = search(genre.name, genre.id)

    /**
     * Apple's top shows for [country], the full chart and no further. Returns
     * ids in rank order - resolve them with [lookup], a page at a time, since
     * a chart carries no feeds.
     */
    suspend fun chartIds(country: String = "us"): List<String> =
        // Deliberately not caught here. Swallowing it returned an empty list,
        // which the repository could not tell from a chart that genuinely had
        // nothing in it, so a failed fetch rendered as "end of top shows" - an
        // empty directory with no hint that anything had gone wrong.
        Http.json.decodeFromString<LegacyChartsResponse>(
            Http.text("$ITUNES/${country.lowercase()}/rss/toppodcasts/limit=$CHART_LIMIT/json")
        ).feed.entry.mapNotNull { it.id.attributes.imId.ifBlank { null } }

    /** Resolve Apple ids to full shows, feed URL included, in one request. */
    suspend fun lookup(ids: List<String>): List<PodcastShow> {
        if (ids.isEmpty()) return emptyList()
        val body = Http.text("$ITUNES/lookup?id=${ids.joinToString(",")}&entity=podcast")
        val found = decode(body).associateBy { it.id }
        // lookup does not preserve the order ids were asked in, and for a chart
        // the order IS the information, so put it back.
        return ids.mapNotNull { found[it] }
    }

    /**
     * A show for a feed URL the user typed. Apple is asked first so the entry
     * gets real artwork and a genre; failing that the feed speaks for itself
     * and [PodcastFeed.load] fills in the rest.
     */
    suspend fun byFeedUrl(feedUrl: String): PodcastShow? {
        val clean = feedUrl.trim()
        if (clean.isBlank()) return null
        runCatching {
            decode(Http.text("$ITUNES/search?media=podcast&entity=podcast&term=${enc(clean)}&limit=5"))
                .firstOrNull { it.feedUrl.equals(clean, ignoreCase = true) }
        }.getOrNull()?.let { return it }
        return PodcastShow(id = feedId(clean), title = clean, feedUrl = clean)
    }

    /** Stable synthetic id for a feed Apple does not list. */
    fun feedId(feedUrl: String): String = "feed:${feedUrl.trim().lowercase().hashCode()}"

    private fun decode(body: String): List<PodcastShow> =
        Http.json.decodeFromString<ItunesResponse>(body)
            .results
            .asSequence()
            .filter { it.feedUrl.isNotBlank() }
            .map { it.toShow() }
            .distinctBy { it.feedUrl }
            .toList()
}

@Serializable
private data class ItunesResponse(
    val resultCount: Int = 0,
    val results: List<ItunesShow> = emptyList(),
)

@Serializable
private data class ItunesShow(
    val collectionId: Long = 0,
    val collectionName: String = "",
    val artistName: String = "",
    val feedUrl: String = "",
    val artworkUrl600: String = "",
    val artworkUrl100: String = "",
    val primaryGenreName: String = "",
    val trackCount: Int = 0,
) {
    fun toShow() = PodcastShow(
        id = collectionId.toString(),
        title = collectionName.trim().ifBlank { "untitled show" },
        feedUrl = feedUrl,
        author = artistName.trim(),
        artwork = artworkUrl600.ifBlank { artworkUrl100 },
        genre = primaryGenreName,
        episodeCount = trackCount,
    )
}

@Serializable
private data class LegacyChartsResponse(val feed: LegacyChartsFeed = LegacyChartsFeed())

@Serializable
private data class LegacyChartsFeed(val entry: List<LegacyChartEntry> = emptyList())

@Serializable
private data class LegacyChartEntry(val id: LegacyChartId = LegacyChartId())

@Serializable
private data class LegacyChartId(val attributes: LegacyChartIdAttributes = LegacyChartIdAttributes())

@Serializable
private data class LegacyChartIdAttributes(
    @SerialName("im:id") val imId: String = "",
)
