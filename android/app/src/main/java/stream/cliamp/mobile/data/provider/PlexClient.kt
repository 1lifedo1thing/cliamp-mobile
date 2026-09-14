package stream.cliamp.mobile.data.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import stream.cliamp.mobile.net.Http
import stream.cliamp.mobile.playback.ResolvedStream

/**
 * Plex is different from the Emby-family servers: it keys off a single
 * `X-Plex-Token` and its library is discovered through `/library/sections`.
 * Its stream URLs anyway want the token in the URL (`?X-Plex-Token=`), so this
 * client bakes it into the signed URL and, like Jellyfin, the default playback
 * data source needs nothing extra.
 *
 * The browse screen is single-library, so the client quietly uses the first
 * music section it finds.
 */
class PlexClient(
    rawUrl: String,
    private val token: String,
) {
    private val base = SubsonicClient.normalise(rawUrl)
    private val auth = "X-Plex-Token=$token"

    // Plex answers XML unless told otherwise. The models below are JSON, so
    // every request asks for JSON up front rather than parsing XML.
    private val jsonHeaders = mapOf("Accept" to "application/json")

    @Volatile private var musicSection: String? = null

    private suspend fun sectionKey(): String {
        musicSection?.let { return it }
        val body = Http.text(get("library/sections"), jsonHeaders)
        val dirs = Http.json.decodeFromString<SectionContainer>(body).mediaContainer.directory
        val key = dirs.firstOrNull { it.type == "artist" }?.key
            ?: dirs.firstOrNull()?.key.orEmpty()
        if (key.isBlank()) error("no plex music library found")
        musicSection = key
        return key
    }

    suspend fun ping(): Result<ProviderIdentity> = withContext(Dispatchers.IO) {
        runCatching {
            val body = Http.text(get("", emptyMap()), jsonHeaders)
            val title = Http.json.decodeFromString<RootContainer>(body).mediaContainer.title.orEmpty()
            ProviderIdentity(name = title.ifBlank { "plex" })
        }
    }

    suspend fun albums(style: String): Result<List<ProviderAlbum>> = withContext(Dispatchers.IO) {
        runCatching {
            val key = sectionKey()
            val body = Http.text(get("library/sections/$key/all", mapOf("type" to "9")), jsonHeaders)
            Http.json.decodeFromString<MetadataContainer<AlbumMeta>>(body).mediaContainer.metadata.map {
                ProviderAlbum(
                    id = it.ratingKey,
                    name = it.title.orEmpty(),
                    artist = it.parentTitle.orEmpty(),
                    songCount = 0,
                    year = it.year ?: 0,
                )
            }
        }
    }

    suspend fun artists(): Result<List<ProviderArtist>> = withContext(Dispatchers.IO) {
        runCatching {
            val key = sectionKey()
            val body = Http.text(get("library/sections/$key/all", mapOf("type" to "8")), jsonHeaders)
            Http.json.decodeFromString<MetadataContainer<ArtistMeta>>(body).mediaContainer.metadata.map {
                ProviderArtist(it.ratingKey, it.title.orEmpty(), 0)
            }
        }
    }

    suspend fun artistAlbums(artistId: String): Result<List<ProviderAlbum>> = withContext(Dispatchers.IO) {
        runCatching {
            val key = sectionKey()
            val body = Http.text(
                get("library/sections/$key/all", mapOf("type" to "9", "artist.id" to artistId)),
                jsonHeaders,
            )
            Http.json.decodeFromString<MetadataContainer<AlbumMeta>>(body).mediaContainer.metadata.map {
                ProviderAlbum(
                    id = it.ratingKey,
                    name = it.title.orEmpty(),
                    artist = it.parentTitle.orEmpty(),
                    songCount = 0,
                    year = it.year ?: 0,
                )
            }
        }
    }

    suspend fun albumTracks(albumId: String): Result<List<ProviderTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = Http.text(get("library/metadata/$albumId/children"), jsonHeaders)
            Http.json.decodeFromString<MetadataContainer<TrackMeta>>(body).mediaContainer.metadata.map { t ->
                val part = t.media.firstOrNull()?.part.orEmpty()
                ProviderTrack(
                    // The opaque ref is the relative Part path, so the resolver
                    // can rebuild a signed stream URL at play time.
                    id = part.firstOrNull()?.key.orEmpty().ifBlank { "/library/metadata/$albumId/unknown" },
                    title = t.title.orEmpty(),
                    artist = t.grandparentTitle.orEmpty().ifBlank { t.originalTitle.orEmpty() },
                    album = t.parentTitle.orEmpty(),
                    duration = t.duration?.div(1000) ?: 0,
                    codec = part.firstOrNull()?.container.orEmpty(),
                )
            }
        }
    }

    fun stream(partPath: String): ResolvedStream =
        ResolvedStream("$base$partPath?$auth")

    fun coverUrl(ratingKey: String, size: Int = 512): String =
        "$base/library/metadata/$ratingKey/thumb?$auth"

    private fun get(path: String, params: Map<String, String> = emptyMap()): String {
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        val base2 = "$base/$path"
        return if (query.isEmpty()) "$base2?$auth" else "$base2?$query&$auth"
    }
}

@Serializable
private data class SectionContainer(@SerialName("MediaContainer") val mediaContainer: Sections)

@Serializable
private data class Sections(@SerialName("Directory") val directory: List<Section> = emptyList())

@Serializable
private data class Section(val key: String = "", val type: String = "", val title: String = "")

@Serializable
private data class RootContainer(@SerialName("MediaContainer") val mediaContainer: Root)

@Serializable
private data class Root(val title: String = "")

@Serializable
private data class MetadataContainer<T>(@SerialName("MediaContainer") val mediaContainer: Metadata<T>)

@Serializable
private data class Metadata<T>(@SerialName("Metadata") val metadata: List<T> = emptyList())

@Serializable
private data class AlbumMeta(
    val ratingKey: String = "",
    val title: String? = null,
    val parentTitle: String? = null,
    val year: Int? = null,
)

@Serializable
private data class ArtistMeta(val ratingKey: String = "", val title: String? = null)

@Serializable
private data class TrackMeta(
    val ratingKey: String = "",
    val title: String? = null,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val originalTitle: String? = null,
    val duration: Int? = null,
    @SerialName("Media") val media: List<MediaMeta> = emptyList(),
)

@Serializable
private data class MediaMeta(@SerialName("Part") val part: List<PartMeta> = emptyList())

@Serializable
private data class PartMeta(val key: String = "", val container: String = "")