package stream.cliamp.mobile.data.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import stream.cliamp.mobile.net.Http
import stream.cliamp.mobile.playback.ResolvedStream

/**
 * Jellyfin speaks the Emby HTTP API, so this one client also covers emby
 * servers (a [providerKey] flag picks the auth header scheme). Tracks are
 * served as files, exactly as [SubsonicClient] does, but authentication lives
 * in an `X-Emby-Token` header rather than in the URL - which is why the
 * stream resolution returns [ResolvedStream] with headers and the playback
 * path knows how to attach them.
 *
 * Two sign-in modes mirror the desktop app:
 *  - an API token, used directly as the auth header;
 *  - a username + password, exchanged once for a token via
 *    `POST /Users/AuthenticateByName` and cached per server for the session.
 */
class JellyfinClient(
    rawUrl: String,
    private val token: String,
    private val user: String,
    private val password: String,
    private val providerKey: String,
) {
    private val base = SubsonicClient.normalise(rawUrl)

    private val authToken: String
        get() = token.ifBlank { cachedToken(base) }.ifBlank { "" }

    /** Returns a short label for the wizard: server name + version. */
    suspend fun ping(): Result<ProviderIdentity> = withContext(Dispatchers.IO) {
        runCatching {
            if (authToken.isBlank()) login()
            // The public info endpoint answers without credentials, so it
            // cannot validate a token. Ping the authenticated endpoint the
            // desktop app uses instead: /Users/Me for Jellyfin,
            // /System/Info for Emby.
            if (providerKey == "emby") {
                val body = Http.text(get("System/Info"))
                val info = Http.json.decodeFromString<PublicSystemInfo>(body)
                ProviderIdentity(
                    name = info.serverName.orEmpty().ifBlank { providerKey },
                    detail = info.version.orEmpty(),
                )
            } else {
                val me = Http.text(get("Users/Me"))
                val user = Http.json.decodeFromString<UserMe>(me)
                if (user.id.isBlank() && user.name.isBlank()) error("jellyfin rejected the credentials")
                val info = runCatching {
                    Http.json.decodeFromString<PublicSystemInfo>(Http.text("$base/System/Info/Public"))
                }.getOrNull()
                ProviderIdentity(
                    name = info?.serverName.orEmpty().ifBlank { providerKey },
                    detail = info?.version.orEmpty(),
                )
            }
        }
    }

    /** [style] is one of the Roots in the browse screen (newest / a-z). */
    suspend fun albums(style: String): Result<List<JellyfinAlbum>> = withContext(Dispatchers.IO) {
        runCatching {
            ensureAuth()
            val sort = if (style == "newest") "DateCreated,SortName" else "SortName"
            val body = Http.text(
                get(
                    "Items",
                    mapOf(
                        "recursive" to "true",
                        "includeItemTypes" to "MusicAlbum",
                        "sortBy" to sort,
                        "sortOrder" to "Ascending",
                        "limit" to "200",
                    ),
                )
            )
            Http.json.decodeFromString<ItemsResult<JellyfinAlbumItem>>(body).items.map {
                JellyfinAlbum(
                    id = it.id,
                    name = it.name,
                    artist = it.albumArtist.orEmpty(),
                    songCount = it.childCount.takeIf { it > 0 } ?: 0,
                    year = it.productionYear ?: 0,
                )
            }
        }
    }

    suspend fun artists(): Result<List<JellyfinArtist>> = withContext(Dispatchers.IO) {
        runCatching {
            ensureAuth()
            val body = Http.text(
                get(
                    "Artists",
                    mapOf("limit" to "200", "sortBy" to "SortName", "sortOrder" to "Ascending"),
                )
            )
            Http.json.decodeFromString<ItemsResult<JellyfinArtistItem>>(body).items.map {
                JellyfinArtist(it.id, it.name, 0)
            }
        }
    }

    suspend fun artistAlbums(artistId: String): Result<List<JellyfinAlbum>> = withContext(Dispatchers.IO) {
        runCatching {
            ensureAuth()
            val body = Http.text(
                get(
                    "Items",
                    mapOf(
                        "recursive" to "true",
                        "includeItemTypes" to "MusicAlbum",
                        "artistIds" to artistId,
                        "sortBy" to "SortName",
                        "sortOrder" to "Ascending",
                    ),
                )
            )
            Http.json.decodeFromString<ItemsResult<JellyfinAlbumItem>>(body).items.map {
                JellyfinAlbum(
                    id = it.id,
                    name = it.name,
                    artist = it.albumArtist.orEmpty(),
                    songCount = it.childCount.takeIf { it > 0 } ?: 0,
                    year = it.productionYear ?: 0,
                )
            }
        }
    }

    suspend fun albumTracks(albumId: String): Result<List<JellyfinTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            ensureAuth()
            val body = Http.text(
                get(
                    "Items",
                    mapOf(
                        "parentId" to albumId,
                        "recursive" to "true",
                        "includeItemTypes" to "Audio",
                        "sortBy" to "ParentIndexNumber,IndexNumber,SortName",
                        "fields" to "RunTimeTicks",
                    ),
                )
            )
            Http.json.decodeFromString<ItemsResult<JellyfinTrackItem>>(body).items.map {
                JellyfinTrack(
                    id = it.id,
                    title = it.name,
                    artist = it.artists.firstOrNull().orEmpty(),
                    album = it.album.orEmpty(),
                    duration = if (it.runTimeTicks > 0) (it.runTimeTicks / 10_000_000).toInt() else 0,
                    bitRate = it.bitRate,
                    suffix = it.container.orEmpty(),
                )
            }
        }
    }

    /**
     * The stream to play: the original file. Jellyfin accepts the token as an
     * `api_key` query parameter (the same route the desktop app uses), so the
     * signed URL carries the credential and the default data source plays it
     * without needing custom headers.
     */
    suspend fun stream(id: String): ResolvedStream {
        ensureAuth()
        val t = authToken
        val q = if (t.isBlank()) "" else "api_key=${enc(t)}"
        return ResolvedStream("$base/Items/$id/Download${if (q.isEmpty()) "" else "?$q"}")
    }

    fun coverUrl(id: String, size: Int = 512): String =
        "$base/Items/$id/Images/Primary?maxWidth=$size"

    private suspend fun ensureAuth() {
        if (authToken.isBlank()) login()
    }

    private suspend fun login() {
        val body = Http.postJson(
            "$base/Users/AuthenticateByName",
            """{"Username":"${esc(user)}","Pw":"${esc(password)}"}""",
        )
        val res = Http.json.decodeFromString<AuthenticateResult>(body)
        val t = res.accessToken.orEmpty()
        if (t.isBlank()) error("jellyfin rejected the credentials")
        cachedToken[base] = t
    }

    private fun get(path: String, params: Map<String, String> = emptyMap()): String {
        val all = buildMap {
            putAll(params)
            authToken.takeIf { it.isNotBlank() }?.let { put("api_key", it) }
        }
        val query = all.entries.joinToString("&") { (k, v) ->
            "$k=" + enc(v)
        }
        return "$base/$path${if (query.isEmpty()) "" else "?$query"}"
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    companion object {
        /** Tokens are exchanged for passwords per server and cached for the session. */
        private val cachedToken = java.util.concurrent.ConcurrentHashMap<String, String>()
        private fun cachedToken(base: String): String = cachedToken[base].orEmpty()
    }
}

/** Authenticated user: `GET /Users/Me` on Jellyfin. */
@Serializable
private data class UserMe(
    @SerialName("Name") val name: String = "",
    @SerialName("Id") val id: String = "",
)

/** A Jellyfin/Emby item collection: `{ "Items": [...] }`. */
@Serializable
private data class ItemsResult<T>(val items: List<T> = emptyList())

@Serializable
private data class PublicSystemInfo(
    @SerialName("ServerName") val serverName: String = "",
    val version: String = "",
)

@Serializable
private data class AuthenticateResult(
    val accessToken: String = "",
)

@Serializable
private data class JellyfinAlbumItem(
    val id: String = "",
    val name: String = "",
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    val childCount: Int = 0,
    val productionYear: Int? = null,
)

@Serializable
private data class JellyfinArtistItem(val id: String = "", val name: String = "")

@Serializable
private data class JellyfinTrackItem(
    val id: String = "",
    val name: String = "",
    val artists: List<String> = emptyList(),
    val album: String = "",
    val runTimeTicks: Long = 0L,
    val bitRate: Int = 0,
    val container: String = "",
)

data class JellyfinAlbum(
    val id: String,
    val name: String,
    val artist: String = "",
    val songCount: Int = 0,
    val year: Int = 0,
)

data class JellyfinArtist(val id: String, val name: String, val albumCount: Int = 0)

data class JellyfinTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val duration: Int = 0,
    val bitRate: Int = 0,
    val suffix: String = "",
)