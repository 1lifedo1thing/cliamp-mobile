package stream.cliamp.mobile.playback

import stream.cliamp.mobile.net.Http

/**
 * Directory entries sometimes point at a playlist file rather than the stream.
 * `.m3u8` is HLS and Media3 handles it natively; plain `.m3u` and `.pls` are
 * just text pointing somewhere else, so we follow them one hop.
 */
object StreamResolver {

    /** Scheme for a provider track whose real URL must be signed on demand. */
    const val PROVIDER_SCHEME = "cliamp-provider://"

    /**
     * Set once at startup. Provider tracks are stored as
     * `cliamp-provider://<accountId>/<trackId>` rather than as a signed URL,
     * because a Subsonic stream URL embeds `t=md5(password+salt)` and those
     * tokens never expire. Baking one into a Station would write a replayable
     * credential into the history and last-station preferences, which are plain
     * files - undoing the point of encrypting the password in the first place.
     */
    @Volatile var providerResolver: (suspend (String, String) -> String?)? = null

    suspend fun resolve(url: String): String {
        if (url.startsWith(PROVIDER_SCHEME)) {
            val ref = url.removePrefix(PROVIDER_SCHEME)
            val accountId = ref.substringBefore('/')
            val trackId = ref.substringAfter('/')
            return providerResolver?.invoke(accountId, trackId) ?: url
        }
        val lower = url.substringBefore('?').lowercase()
        return when {
            lower.endsWith(".m3u8") -> url
            lower.endsWith(".m3u") -> firstUrl(runCatching { Http.text(url) }.getOrNull()) ?: url
            lower.endsWith(".pls") -> firstPlsUrl(runCatching { Http.text(url) }.getOrNull()) ?: url
            else -> url
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
