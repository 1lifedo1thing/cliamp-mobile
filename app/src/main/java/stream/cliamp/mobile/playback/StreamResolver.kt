package stream.cliamp.mobile.playback

import stream.cliamp.mobile.net.Http

/**
 * Directory entries sometimes point at a playlist file rather than the stream.
 * `.m3u8` is HLS and Media3 handles it natively; plain `.m3u` and `.pls` are
 * just text pointing somewhere else, so we follow them one hop.
 */
object StreamResolver {

    suspend fun resolve(url: String): String {
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
