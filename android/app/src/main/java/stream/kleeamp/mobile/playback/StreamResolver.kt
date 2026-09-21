package stream.kleeamp.mobile.playback

import kotlinx.coroutines.withTimeoutOrNull
import stream.kleeamp.mobile.net.Http
import java.io.IOException

/**
 * A URL ready to hand to the player, plus the HTTP headers its request
 * must carry. Most streams need nothing beyond the shared User-Agent, but
 * self-hosted providers (Jellyfin, Emby, Plex, Audiobookshelf) authenticate
 * in the headers - a Bearer token, an `X-Emby-*`/`X-Plex-*` header - rather
 * than in the URL, so resolution has to be able to carry both.
 */
data class ResolvedStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Directory entries sometimes point at a playlist file rather than the stream.
 * `.m3u8` is HLS and Media3 handles it natively; plain `.m3u` and `.pls` are
 * just text pointing somewhere else, so we follow them one hop.
 */
object StreamResolver {

    /** Scheme for a provider track whose real URL must be resolved on demand. */
    const val PROVIDER_SCHEME = "cliamp-provider://"

    /**
     * Set once at startup. Provider tracks are stored as
     * `cliamp-provider://<accountId>/<trackId>` rather than as a signed URL,
     * because provider stream URLs and headers can embed credentials (a Subsonic
     * `t=md5(password+salt)` token, a Bearer token) that never expire. Baking
     * one into a Station would write a replayable credential into the history
     * and last-station preferences, which are plain files - undoing the point
     * of encrypting them in the first place.
     */
    @Volatile var providerResolver: (suspend (String, String) -> ResolvedStream?)? = null

    /**
     * Set once at startup. A fetched episode plays from its file instead of
     * the network - the offline half of the downloads playlist. Returns an
     * absolute path for a live file, or null to stream as usual.
     */
    @Volatile var downloadLookup: ((String) -> String?)? = null

    /**
     * Aggregate bound on one resolution: the provider hop is ensureAuth plus
     * queries in sequence, each with its own generous call timeout, so
     * without this a sick server parks the nav job and retaps queue behind
     * it. Falls back to nothing - a timeout surfaces as a resolve error
     * like any other failed hop.
     */
    const val RESOLVE_TIMEOUT_MS = 60_000L

    suspend fun resolve(url: String): ResolvedStream =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolveUnsafe(url) }
            ?: throw IOException("stream resolve timed out")

    private suspend fun resolveUnsafe(url: String): ResolvedStream {
        if (url.startsWith(PROVIDER_SCHEME)) {
            val ref = url.removePrefix(PROVIDER_SCHEME)
            val accountId = ref.substringBefore('/')
            val trackId = ref.substringAfter('/')
            return providerResolver?.invoke(accountId, trackId) ?: ResolvedStream(url)
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            downloadLookup?.invoke(url)?.let { path ->
                return ResolvedStream(java.io.File(path).toURI().toString())
            }
        }
        val lower = url.substringBefore('?').lowercase()
        return when {
            lower.endsWith(".m3u8") -> ResolvedStream(url)
            lower.endsWith(".m3u") -> ResolvedStream(firstUrl(runCatching { Http.text(url) }.getOrNull()) ?: url)
            lower.endsWith(".pls") -> ResolvedStream(firstPlsUrl(runCatching { Http.text(url) }.getOrNull()) ?: url)
            else -> ResolvedStream(url)
        }
    }

    private fun firstUrl(body: String?): String? = body
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") && it.contains("://") }

    private fun firstPlsUrl(body: String?): String? = body
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("File", ignoreCase = true) && it.contains('=') }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.contains("://") }
}