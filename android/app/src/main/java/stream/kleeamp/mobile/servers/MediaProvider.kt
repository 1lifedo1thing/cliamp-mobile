package stream.kleeamp.mobile.servers

import androidx.media3.common.util.UnstableApi
import stream.kleeamp.mobile.playback.ResolvedStream
import stream.kleeamp.mobile.playback.SftpDataSource

/**
 * One protocol's stream resolution: a stored provider track id in, a
 * playable URL (plus auth headers) out. URLs, signing and the SFTP URI
 * format are owned by the clients — implementations only route.
 */
interface MediaProvider {
    val key: String
    suspend fun stream(account: ProviderAccount, trackId: String): ResolvedStream
}

/** Plain Subsonic servers; also the fallback for unknown provider keys. */
object SubsonicMediaProvider : MediaProvider {
    override val key = "subsonic"
    override suspend fun stream(account: ProviderAccount, trackId: String): ResolvedStream =
        ResolvedStream(account.subsonic().streamUrl(trackId))
}

/** Jellyfin and Emby share the client; the router aliases "emby" here. */
object JellyfinMediaProvider : MediaProvider {
    override val key = "jellyfin"
    override suspend fun stream(account: ProviderAccount, trackId: String): ResolvedStream =
        account.jellyfin().stream(trackId)
}

object PlexMediaProvider : MediaProvider {
    override val key = "plex"
    override suspend fun stream(account: ProviderAccount, trackId: String): ResolvedStream =
        account.plex().stream(trackId)
}

object AudiobookshelfMediaProvider : MediaProvider {
    override val key = "abs"
    override suspend fun stream(account: ProviderAccount, trackId: String): ResolvedStream =
        account.audiobookshelf().stream(trackId)
}

object LyrionMediaProvider : MediaProvider {
    override val key = "lyrion"
    override suspend fun stream(account: ProviderAccount, trackId: String): ResolvedStream =
        account.lyrion().stream(trackId)
}

/**
 * Not an HTTP URL at all: the track id is the remote path, and the data
 * source opens it over the account's SSH connection.
 */
object SshMediaProvider : MediaProvider {
    override val key = "ssh"
    // SftpDataSource extends Media3's unstable data-source API.
    @UnstableApi
    override suspend fun stream(account: ProviderAccount, trackId: String): ResolvedStream =
        ResolvedStream(SftpDataSource.uriFor(account.id, trackId))
}
