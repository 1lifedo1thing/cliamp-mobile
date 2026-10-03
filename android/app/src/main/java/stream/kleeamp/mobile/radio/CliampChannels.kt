package stream.kleeamp.mobile.radio

import kotlinx.serialization.Serializable
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.net.Http

/**
 * cliamp radio channels as playlists, mirroring desktop `ChannelProvider`:
 * `stations` lists every channel with its song count, and a channel with
 * songs opens its `tracks_url` as seekable files instead of the live stream.
 * Anything else plays the stream exactly like before.
 */
object CliampChannels {

    const val CHANNELS_URL = "${CliampRadio.BASE}/stations"

    /** One channel. A channel with songs opens as its track list. */
    @Serializable
    data class Channel(
        val id: String,
        val name: String,
        val stream: String,
        val trackCount: Int = 0,
        val tracksUrl: String = "",
        val description: String = "",
        val genre: String = "",
    ) {
        val hasTracks: Boolean get() = trackCount > 0 && tracksUrl.isNotBlank()
    }

    @Serializable
    private data class ChannelJson(
        val id: String = "",
        val name: String = "",
        val description: String = "",
        val genre: String = "",
        val stream: String = "",
        val tracks: Int = 0,
        val tracks_url: String = "",
    )

    @Serializable
    private data class ChannelsJson(val stations: List<ChannelJson> = emptyList())

    @Serializable
    private data class TrackJson(
        val id: String = "",
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val url: String = "",
        val cover: String = "",
        val artwork: String = "",
    )

    @Serializable
    private data class TracksJson(val tracks: List<TrackJson> = emptyList())

    /**
     * Channels from a `/stations` body, songs-first like desktop. Pure so
     * the validation and ordering are unit-tested on the JVM.
     */
    internal fun parseChannels(body: String): List<Channel> {
        val doc = runCatching { Http.json.decodeFromString<ChannelsJson>(body) }.getOrNull()
            ?: return emptyList()
        val songs = mutableListOf<Channel>()
        val live = mutableListOf<Channel>()
        doc.stations.forEach { s ->
            val id = s.id.trim()
            if (id.isEmpty() || !isHttp(s.stream)) return@forEach
            val tracksUrl = s.tracks_url.takeIf { isHttp(it) }.orEmpty()
            val channel = Channel(
                id = id,
                name = s.name.ifBlank { id },
                stream = s.stream,
                trackCount = s.tracks.coerceAtLeast(0),
                tracksUrl = tracksUrl,
                description = s.description,
                genre = s.genre,
            )
            if (channel.hasTracks) songs += channel else live += channel
        }
        return songs + live
    }

    /**
     * A channel's tracks from a `tracks_url` body, as player-ready stations.
     * The files are seekable songs, not the live stream. Pure for JVM tests.
     */
    internal fun parseTracks(body: String, channel: Channel): List<Station> {
        val doc = runCatching { Http.json.decodeFromString<TracksJson>(body) }.getOrNull()
            ?: return emptyList()
        return doc.tracks.mapNotNull { t ->
            if (!isHttp(t.url)) return@mapNotNull null
            Station(
                id = "cliamp:${channel.id}:${t.id.ifBlank { t.url.hashCode().toString() }}",
                name = t.title.ifBlank { "untitled track" },
                url = t.url,
                source = StationSource.Cliamp,
                slug = channel.id,
                artist = t.artist,
                album = t.album,
                cover = t.cover.ifBlank { t.artwork },
            )
        }
    }

    private val trackCache = mutableMapOf<String, List<Station>>()
    private val trackCacheLock = Any()

    suspend fun fetchChannels(): List<Channel> =
        parseChannels(Http.text(CHANNELS_URL))

    /** Channel tracks with a memory cache; [refresh] re-reads the network. */
    suspend fun fetchTracks(channel: Channel, refresh: Boolean = false): List<Station> {
        if (!refresh) {
            synchronized(trackCacheLock) { trackCache[channel.id]?.let { return it } }
        }
        val tracks = parseTracks(Http.text(channel.tracksUrl), channel)
        if (tracks.isNotEmpty()) {
            synchronized(trackCacheLock) { trackCache[channel.id] = tracks }
        }
        return tracks
    }

    /**
     * Every channel track list loaded so far, for search: only channels the
     * user already opened are in here, so search answers from memory and
     * never fans a query out into one fetch per channel.
     */
    fun cachedTracks(): List<Station> =
        synchronized(trackCacheLock) { trackCache.values.flatten() }

    /** One channel's memory list, or null when it was never loaded. */
    fun cachedTracksFor(channelId: String): List<Station>? =
        synchronized(trackCacheLock) { trackCache[channelId] }

    /** Seeds the memory list, so a disk snapshot reads like a fresh load. */
    fun seedTracks(channelId: String, tracks: List<Station>) {
        if (tracks.isEmpty()) return
        synchronized(trackCacheLock) { trackCache[channelId] = tracks }
    }

    private fun isHttp(raw: String): Boolean {
        if (raw.isBlank()) return false
        val scheme = raw.substringBefore(':').lowercase()
        return (scheme == "http" || scheme == "https") && '.' in raw.substringAfter(':')
    }
}
