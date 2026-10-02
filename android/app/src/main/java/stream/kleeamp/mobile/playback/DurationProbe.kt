package stream.kleeamp.mobile.playback

import android.media.MediaMetadataRetriever
import stream.kleeamp.mobile.net.Http

/**
 * File lengths the server never sends, read from the file headers: one
 * header fetch per URL, so callers only probe tracks the user can actually
 * see. Anything unreadable reads 0 and stays unknown.
 */
object DurationProbe {
    fun probeMs(url: String): Long {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return 0L
        val retriever = try {
            MediaMetadataRetriever()
        } catch (t: Throwable) {
            return 0L
        }
        return try {
            retriever.setDataSource(url, mapOf("User-Agent" to Http.USER_AGENT))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0 } ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }
}
