package stream.kleeamp.mobile.podcasts

import kotlinx.serialization.Serializable
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

/**
 * A show, as opposed to an episode.
 *
 * Episodes reach the player as [Station]s, like local files and provider
 * tracks do, so they need no type of their own. A show does: it is the thing
 * you subscribe to, the thing a feed is fetched for, and the thing the
 * directory returns. Radio has no equivalent - a station is both the
 * subscription and the stream - which is the one place the podcast model has
 * to be deeper than the radio one.
 */
@Serializable
data class PodcastShow(
    /** Apple's collection id, or `feed:<hash>` for a feed added by URL. */
    val id: String,
    val title: String,
    val feedUrl: String,
    val author: String = "",
    val artwork: String = "",
    val genre: String = "",
    val episodeCount: Int = 0,
    val description: String = "",
) {
    /** `jack rhysider · technology · 198 eps`, skipping what the directory omitted. */
    val meta: String
        get() = buildList {
            if (author.isNotBlank()) add(author.lowercase())
            if (genre.isNotBlank()) add(genre.lowercase())
            if (episodeCount > 0) add("$episodeCount eps")
        }.joinToString(" · ")
}

/**
 * One entry out of a feed. Everything here comes from the RSS item; nothing is
 * derived from the show except artwork, which falls back to the channel's when
 * an episode carries none.
 *
 * [publishedAt] and [number] stay on this type rather than being folded into
 * [Station]. A Station is what the player needs, and the player needs neither;
 * the episode list is the only screen that shows them, and it holds the real
 * episodes anyway.
 */
@Serializable
data class PodcastEpisode(
    val guid: String,
    val title: String,
    val audioUrl: String,
    val durationMs: Long = 0L,
    val publishedAt: Long = 0L,
    val artwork: String = "",
    val description: String = "",
    val number: Int = 0,
    val season: Int = 0,
    /** `full`, `trailer` or `bonus`, per itunes:episodeType. */
    val type: String = "full",
    val codec: String = "",
) {
    val isFull: Boolean get() = type.isBlank() || type.equals("full", ignoreCase = true)
}

/**
 * The episode as the player sees it.
 *
 * [Station.homepage] is deliberately left empty. StationArtSource scrapes a
 * homepage for an og:image before it will look at anything else, which is the
 * right order for radio and exactly wrong here: a feed hands us real artwork,
 * so `cover` is authoritative and there is nothing to discover.
 */
fun PodcastEpisode.toStation(show: PodcastShow): Station = Station(
    id = "pod:${show.id}:$guid",
    name = title.ifBlank { "untitled episode" },
    url = audioUrl,
    source = StationSource.Podcast,
    slug = show.id,
    tags = show.genre,
    codec = codec,
    cover = artwork.ifBlank { show.artwork },
    artist = show.title,
    durationMs = durationMs,
)

/** Saved listening position for one episode, keyed by its audio URL. */
data class EpisodeProgress(
    val url: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
) {
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
