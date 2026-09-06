package stream.cliamp.mobile.playback

import stream.cliamp.mobile.net.Http

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

    suspend fun resolve(url: String): ResolvedStream {
        if (url.startsWith(PROVIDER_SCHEME)) {
            val ref = url.removePrefix(PROVIDER_SCHEME)
            val accountId = ref.substringBefore('/')
            val trackId = ref.substringAfter('/')
            return providerResolver?.invoke(accountId, trackId) ?: ResolvedStream(url)
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