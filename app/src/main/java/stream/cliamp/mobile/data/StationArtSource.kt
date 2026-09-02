package stream.cliamp.mobile.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import stream.cliamp.mobile.net.Http
import java.net.URI

/**
 * Radio streams carry no cover art, so the next best thing is the station's own
 * branding: the og:image on its homepage, then its apple-touch-icon, then the
 * favicon the directory recorded.
 *
 * cliamp's own channels are deliberately excluded. cliamp.stream does have an
 * og:image, but it is a 1200x630 marketing screenshot of the desktop app; square
 * cropped into a player it is an unreadable smear of tiny text. The generated
 * plate is per-channel and already square, so it wins there.
 */
object StationArtSource {

    private const val MAX_HTML = 192 * 1024
    private const val MAX_IMAGE = 4 * 1024 * 1024
    private const val TARGET = 512

    /** Formats BitmapFactory cannot decode, however cheerfully they are served. */
    private val undecodable = setOf("image/x-icon", "image/vnd.microsoft.icon", "image/svg+xml")

    private val resolved = LruCache<String, String>(128)
    private val bitmaps = LruCache<String, Bitmap>(8)
    private val misses = LruCache<String, Boolean>(128)

    suspend fun bitmapFor(station: Station): Bitmap? {
        if (station.source == StationSource.Cliamp) return null
        bitmaps.get(station.id)?.let { return it }
        if (misses.get(station.id) == true) return null

        val url = imageUrl(station)
        if (url == null) {
            misses.put(station.id, true)
            return null
        }
        val bmp = download(url)
        if (bmp == null) misses.put(station.id, true) else bitmaps.put(station.id, bmp)
        return bmp
    }

    /**
     * Art whose URL we already know, rather than one that has to be discovered
     * from a homepage. Provider covers arrive this way: getCoverArt gives a
     * real, already-signed URL, so none of the og:image guessing applies.
     */
    suspend fun bitmapForUrl(url: String): Bitmap? {
        if (url.isBlank()) return null
        bitmaps.get(url)?.let { return it }
        if (misses.get(url) == true) return null
        val bmp = download(url)
        if (bmp == null) misses.put(url, true) else bitmaps.put(url, bmp)
        return bmp
    }

    private suspend fun imageUrl(station: Station): String? {
        resolved.get(station.id)?.let { return it }
        val fromPage = station.homepage.takeIf { it.startsWith("http") }?.let { scrape(it) }
        val candidate = fromPage ?: station.favicon.takeIf { it.startsWith("http") }
        if (candidate != null) resolved.put(station.id, candidate)
        return candidate
    }

    // Deliberately not a full HTML parse. We read the head, pull the first
    // usable meta tag, and stop; a parser dependency would cost more than the
    // three regexes it replaces.
    private val ogTag = Regex(
        """<meta[^>]+(?:property|name)\s*=\s*["'](?:og:image(?::secure_url)?|twitter:image(?::src)?)["'][^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val appleTag = Regex(
        """<link[^>]+rel\s*=\s*["'][^"']*apple-touch-icon[^"']*["'][^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val contentAttr = Regex("""content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val hrefAttr = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private suspend fun scrape(homepage: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(homepage)
                .header("User-Agent", Http.USER_AGENT)
                .header("Accept", "text/html")
                .build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val ct = r.header("Content-Type").orEmpty()
                if (!ct.contains("html", ignoreCase = true)) return@use null
                val head = r.body.source().let { src ->
                    src.request(MAX_HTML.toLong())
                    src.buffer.snapshot(minOf(MAX_HTML.toLong(), src.buffer.size).toInt()).utf8()
                }
                val raw = ogTag.find(head)?.let { contentAttr.find(it.value)?.groupValues?.get(1) }
                    ?: appleTag.find(head)?.let { hrefAttr.find(it.value)?.groupValues?.get(1) }
                raw?.let { absolute(homepage, it) }
            }
        }.getOrNull()
    }

    private fun absolute(base: String, ref: String): String? = runCatching {
        URI(base).resolve(ref.trim()).toString().takeIf { it.startsWith("http") }
    }.getOrNull()

    private suspend fun download(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val ct = r.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                if (ct.isNotEmpty() && (!ct.startsWith("image/") || ct in undecodable)) return@use null
                val bytes = r.body.byteStream().readAtMost(MAX_IMAGE) ?: return@use null
                if (bytes.size < 64) return@use null
                decodeScaled(bytes)
            }
        }.getOrNull()
    }

    /** Reads up to [limit], and gives up rather than buffering something huge. */
    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET && bounds.outHeight / (sample * 2) >= TARGET) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}

/** Tiny access-ordered cache; the platform LruCache is fine but this keeps nulls meaningful. */
class LruCache<K, V>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }
    @Synchronized fun get(key: K): V? = map[key]
    @Synchronized fun put(key: K, value: V) { map[key] = value }
}
