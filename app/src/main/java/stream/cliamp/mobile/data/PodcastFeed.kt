package stream.cliamp.mobile.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import stream.cliamp.mobile.net.Http
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * RSS, pulled rather than loaded.
 *
 * Real feeds measured while building this: 460 KB / 59 episodes, 1 MB / 180,
 * and 4.4 MB / 865. Reading one of those into a string and handing it to a
 * document parser costs several megabytes of peak heap for a list where only
 * the newest entries are ever looked at, so this walks the stream with
 * [XmlPullParser] and stops at [MAX_EPISODES]. Feeds are newest-first by
 * convention, so stopping early drops the oldest, which is the right end.
 *
 * No XML dependency is added. Android ships a pull parser, and the alternative
 * would be a library to read six element names.
 */
object PodcastFeed {

    private const val ITUNES_NS = "http://www.itunes.com/dtds/podcast-1.0.dtd"

    /** Newest episodes kept per feed. The oldest of an 865-entry feed is noise. */
    const val MAX_EPISODES = 300

    /** The feed's own view of the show, plus its episodes. */
    data class Loaded(val show: PodcastShow, val episodes: List<PodcastEpisode>)

    suspend fun load(show: PodcastShow): Result<Loaded> = withContext(Dispatchers.IO) {
        runCatching {
            val response = Http.call(show.feedUrl)
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                error("HTTP $code")
            }
            response.use { parse(show, it.body.byteStream()) }
        }
    }

    private fun parse(base: PodcastShow, input: InputStream): Loaded {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        // A null encoding lets the parser honour the XML declaration, which is
        // the only thing that knows: feeds arrive as utf-8 and iso-8859-1 alike.
        parser.setInput(input, null)

        var chTitle = ""
        var chAuthor = ""
        var chArtwork = ""
        var chDescription = ""
        var chGenre = ""

        val episodes = ArrayList<PodcastEpisode>()
        var inItem = false
        var inChannelImage = false

        var guid = ""
        var title = ""
        var audio = ""
        var duration = 0L
        var published = 0L
        var artwork = ""
        var description = ""
        var number = 0
        var season = 0
        var type = "full"
        var codec = ""

        fun resetItem() {
            guid = ""; title = ""; audio = ""; duration = 0L; published = 0L
            artwork = ""; description = ""; number = 0; season = 0; type = "full"; codec = ""
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name ?: ""
            val ns = parser.namespace ?: ""
            when (event) {
                XmlPullParser.START_TAG -> when {
                    name == "item" -> { inItem = true; resetItem() }

                    inItem -> when {
                        ns == ITUNES_NS -> when (name) {
                            "duration" -> duration = parseDuration(text(parser))
                            "image" -> artwork = parser.getAttributeValue(null, "href").orEmpty().trim()
                            "episode" -> number = text(parser).trim().toIntOrNull() ?: 0
                            "season" -> season = text(parser).trim().toIntOrNull() ?: 0
                            "episodeType" -> type = text(parser).trim()
                            "title" -> if (title.isBlank()) title = text(parser).trim()
                            "summary" -> if (description.isBlank()) description = strip(text(parser))
                        }
                        name == "title" -> title = text(parser).trim()
                        name == "guid" -> guid = text(parser).trim()
                        name == "pubDate" -> published = parseDate(text(parser))
                        name == "description" -> if (description.isBlank()) description = strip(text(parser))
                        name == "enclosure" -> {
                            val url = parser.getAttributeValue(null, "url").orEmpty().trim()
                            // Only the first enclosure counts, and only if it is
                            // audio: some feeds attach artwork the same way.
                            val mime = parser.getAttributeValue(null, "type").orEmpty()
                            if (audio.isBlank() && url.isNotBlank() && isAudio(mime, url)) {
                                audio = url
                                codec = codecOf(mime, url)
                            }
                        }
                    }

                    ns == ITUNES_NS -> when (name) {
                        "image" -> if (chArtwork.isBlank()) {
                            chArtwork = parser.getAttributeValue(null, "href").orEmpty().trim()
                        }
                        "author" -> if (chAuthor.isBlank()) chAuthor = text(parser).trim()
                        "summary" -> if (chDescription.isBlank()) chDescription = strip(text(parser))
                        "category" -> if (chGenre.isBlank()) {
                            chGenre = parser.getAttributeValue(null, "text").orEmpty().trim()
                        }
                    }

                    name == "image" -> inChannelImage = true
                    name == "url" -> if (inChannelImage && chArtwork.isBlank()) chArtwork = text(parser).trim()
                    name == "title" -> if (chTitle.isBlank()) chTitle = text(parser).trim()
                    name == "description" -> if (chDescription.isBlank()) chDescription = strip(text(parser))
                }

                XmlPullParser.END_TAG -> when {
                    name == "item" && inItem -> {
                        inItem = false
                        if (audio.isNotBlank()) {
                            episodes += PodcastEpisode(
                                // guid is optional and wildly inconsistent
                                // between hosts, so the audio URL stands in:
                                // it is the one field an episode must have.
                                guid = guid.ifBlank { audio },
                                title = title,
                                audioUrl = audio,
                                durationMs = duration,
                                publishedAt = published,
                                artwork = artwork,
                                description = description,
                                number = number,
                                season = season,
                                type = type,
                                codec = codec,
                            )
                        }
                    }
                    name == "image" -> inChannelImage = false
                }
            }
            if (episodes.size >= MAX_EPISODES) break
            event = parser.next()
        }

        // Apple's copy of a show is usually better than the feed's (real
        // artwork, a tidy genre), so the feed only fills in what is missing.
        val show = base.copy(
            title = base.title.ifBlank { chTitle }.ifBlank { "untitled show" },
            author = base.author.ifBlank { chAuthor },
            artwork = base.artwork.ifBlank { chArtwork },
            genre = base.genre.ifBlank { chGenre },
            description = base.description.ifBlank { chDescription },
            episodeCount = if (base.episodeCount > 0) base.episodeCount else episodes.size,
        )
        return Loaded(show, episodes)
    }

    /**
     * [XmlPullParser.nextText] on an element that turned out to be empty and
     * self-closed throws rather than returning "", which one malformed entry
     * should not cost the whole feed.
     */
    private fun text(parser: XmlPullParser): String =
        runCatching { parser.nextText() }.getOrDefault("")

    /**
     * `01:35:02`, `36:12` and `2712` all appear in the wild; the spec allows
     * all three. Every feed measured used the colon forms, and the bare-seconds
     * form is the one that would silently become a 2712 hour episode if it were
     * treated as HH.
     */
    fun parseDuration(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0L
        if (!s.contains(':')) {
            val seconds = s.toDoubleOrNull() ?: return 0L
            return (seconds * 1000).toLong().coerceAtLeast(0L)
        }
        val parts = s.split(':').map { it.trim().toLongOrNull() ?: 0L }
        val seconds = when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0L
        }
        return (seconds * 1000).coerceAtLeast(0L)
    }

    private val dateFormats: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.US),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.US),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.US),
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
    )

    /**
     * RFC 822 dates, loosely. Feeds measured differed on the day being padded
     * (`01 Sep` against `2 Sep`) and on the zone being an offset or a name, so
     * this tries the strict parser first and a short list of shapes after.
     */
    fun parseDate(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0L
        dateFormats.forEach { f ->
            runCatching { return ZonedDateTime.parse(s, f).toInstant().toEpochMilli() }
        }
        return 0L
    }

    private fun isAudio(mime: String, url: String): Boolean {
        if (mime.startsWith("audio", ignoreCase = true)) return true
        if (mime.isNotBlank()) return false
        val path = url.substringBefore('?').lowercase()
        return listOf(".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac").any { path.endsWith(it) }
    }

    private fun codecOf(mime: String, url: String): String = when {
        mime.contains("mpeg", true) -> "mp3"
        mime.contains("mp4", true) || mime.contains("m4a", true) -> "m4a"
        mime.contains("aac", true) -> "aac"
        mime.contains("opus", true) -> "opus"
        mime.contains("ogg", true) || mime.contains("vorbis", true) -> "ogg"
        mime.contains("wav", true) -> "wav"
        mime.contains("flac", true) -> "flac"
        else -> url.substringBefore('?').substringAfterLast('.', "").take(4).lowercase()
    }

    private val tags = Regex("<[^>]+>")
    private val spaces = Regex("\\s+")

    /**
     * Show notes are HTML and are rendered as one line of monospace, so the
     * markup goes. Deliberately not a parse - the same call this codebase makes
     * for og:image discovery, for the same reason.
     */
    private fun strip(raw: String): String = raw
        .replace(tags, " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace(spaces, " ")
        .trim()
}
