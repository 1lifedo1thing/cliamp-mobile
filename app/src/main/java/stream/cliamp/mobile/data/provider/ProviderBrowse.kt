package stream.cliamp.mobile.data.provider

import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.playback.StreamResolver

/**
 * Provider-independent shapes for the browse screen. The API clients (Subsonic,
 * Jellyfin/Emby, ...) each speak their own schema; this is the small vocabulary
 * the screen needs, and each provider's track converts to a [Station] the same
 * way.
 */
data class ProviderAlbum(
    val id: String,
    val name: String,
    val artist: String = "",
    val songCount: Int = 0,
    val year: Int = 0,
)

data class ProviderArtist(val id: String, val name: String, val albumCount: Int = 0)

data class ProviderTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val duration: Int = 0,
    val codec: String = "",
    val bitrate: Int = 0,
)

fun ProviderTrack.toStation(account: ProviderAccount, cover: String = ""): Station = Station(
    id = "prov:${account.id}:$id",
    name = title.ifBlank { "untitled" },
    url = "${StreamResolver.PROVIDER_SCHEME}${account.id}/$id",
    source = StationSource.Provider,
    artist = artist,
    album = album,
    durationMs = duration * 1000L,
    codec = codec,
    bitrate = bitrate,
    cover = cover,
)

/**
 * The operations the browse screen needs from any provider. Each provider is a
 * thin adapter over its API client; the screen never sees provider types.
 */
interface ProviderBrowseClient {
    /** [style] is a browse root label; providers map it to their own sort. */
    suspend fun albums(style: String): Result<List<ProviderAlbum>>
    suspend fun artists(): Result<List<ProviderArtist>>
    suspend fun artistAlbums(artistId: String): Result<List<ProviderAlbum>>
    suspend fun albumTracks(albumId: String): Result<List<ProviderTrack>>
    /** Favourite/starred tracks; empty list when the provider has no such concept. */
    suspend fun starred(): Result<List<ProviderTrack>>
    fun trackCover(id: String): String
}

fun ProviderAccount.browseClient(): ProviderBrowseClient = when (providerKey) {
    "jellyfin", "emby" -> JellyfinBrowseClient(this, jellyfin())
    "plex" -> PlexBrowseClient(plex())
    else -> SubsonicBrowseClient(this, subsonic())
}

private class SubsonicBrowseClient(
    private val account: ProviderAccount,
    private val client: SubsonicClient,
) : ProviderBrowseClient {
    override suspend fun albums(style: String): Result<List<ProviderAlbum>> =
        client.albums(style).map { objs ->
            objs.map { ProviderAlbum(it.id, it.name, it.artist, it.songCount, it.year) }
        }

    override suspend fun artists(): Result<List<ProviderArtist>> =
        client.artists().map { objs -> objs.map { ProviderArtist(it.id, it.name, it.albumCount) } }

    override suspend fun artistAlbums(artistId: String): Result<List<ProviderAlbum>> =
        client.artistAlbums(artistId).map { objs ->
            objs.map { ProviderAlbum(it.id, it.name, it.artist, it.songCount, it.year) }
        }

    override suspend fun albumTracks(albumId: String): Result<List<ProviderTrack>> =
        client.albumTracks(albumId).map { objs ->
            objs.map { ProviderTrack(it.id, it.title, it.artist, it.album, it.duration, it.suffix, it.bitRate) }
        }

    override suspend fun starred(): Result<List<ProviderTrack>> =
        client.starred().map { objs ->
            objs.map { ProviderTrack(it.id, it.title, it.artist, it.album, it.duration, it.suffix, it.bitRate) }
        }

    override fun trackCover(id: String): String = client.coverArtUrl(id)
}

private class JellyfinBrowseClient(
    private val account: ProviderAccount,
    private val client: JellyfinClient,
) : ProviderBrowseClient {
    override suspend fun albums(style: String): Result<List<ProviderAlbum>> =
        client.albums(style).map { objs ->
            objs.map { ProviderAlbum(it.id, it.name, it.artist, it.songCount, it.year) }
        }

    override suspend fun artists(): Result<List<ProviderArtist>> =
        client.artists().map { objs -> objs.map { ProviderArtist(it.id, it.name, 0) } }

    override suspend fun artistAlbums(artistId: String): Result<List<ProviderAlbum>> =
        client.artistAlbums(artistId).map { objs ->
            objs.map { ProviderAlbum(it.id, it.name, it.artist, it.songCount, it.year) }
        }

    override suspend fun albumTracks(albumId: String): Result<List<ProviderTrack>> =
        client.albumTracks(albumId).map { objs ->
            objs.map { ProviderTrack(it.id, it.title, it.artist, it.album, it.duration, it.suffix, it.bitRate) }
        }

    override suspend fun starred(): Result<List<ProviderTrack>> =
        Result.success(emptyList())

    override fun trackCover(id: String): String = client.coverUrl(id)
}

private class PlexBrowseClient(
    private val client: PlexClient,
) : ProviderBrowseClient {
    override suspend fun albums(style: String): Result<List<ProviderAlbum>> = client.albums(style)
    override suspend fun artists(): Result<List<ProviderArtist>> = client.artists()
    override suspend fun artistAlbums(artistId: String): Result<List<ProviderAlbum>> =
        client.artistAlbums(artistId)
    override suspend fun albumTracks(albumId: String): Result<List<ProviderTrack>> =
        client.albumTracks(albumId)
    override suspend fun starred(): Result<List<ProviderTrack>> = Result.success(emptyList())
    override fun trackCover(id: String): String = client.coverUrl(id)
}

private fun <T, R> Result<List<T>>.map(t: (List<T>) -> R): Result<R> =
    fold(onSuccess = { Result.success(t(it)) }, onFailure = { Result.failure(it) })