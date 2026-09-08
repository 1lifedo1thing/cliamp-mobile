package stream.cliamp.mobile.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import stream.cliamp.mobile.net.Http
import java.net.URI

/**
 * Cover work - reading files, MediaMetadataRetriever, decoding - all funnelled
 * through one thread pool capped at four workers. A fast-scrolled list otherwise
 * fires a dozen concurrent decodes that allocate many megabytes of bitmaps and
 * saturate the disk, spiking memory pressure so hard that the whole app stalls
 * and ANRs. One shared, narrow pool keeps that bounded and predictable.
 */
val CoverIo = Dispatchers.IO.limitedParallelism(4)

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

    /** Thumbnails (row icons, the mini player) never need full detail. */
    private const val TARGET_SMALL = 96

    /** Formats BitmapFactory cannot decode, however cheerfully they are served.
     * ICO is served as image/x-icon or image/vnd.microsoft.icon and decodes
     * fine, so only SVG stays on the block list. */
    private val undecodable = setOf("image/svg+xml")

    private val resolved = LruCache<String, String>(128)
    private val bitmaps = LruCache<String, Bitmap>(128)
    private val smallBitmaps = LruCache<String, Bitmap>(192)
    // Key -> when its art last failed. A failure is not permanent: a cover
    // that times out on a cold-start stampede still gets another try once the
    // backoff passes, so a station that does have art ends up showing it.
    private val missedAt = LruCache<String, Long>(256)

    /** How long a failed cover is left alone before it is tried again. */
    private const val MISS_RETRY_MS = 60_000L

    private fun noteMiss(key: String) = missedAt.put(key, System.currentTimeMillis())
    private fun isOut(key: String): Boolean =
        missedAt.get(key)?.let { System.currentTimeMillis() - it < MISS_RETRY_MS } ?: false

    /** Directory holding decoded embedded-art bytes, keyed by audio path hash. */
    private var cacheDir: java.io.File? = null

    /** Call once at startup with an application context. */
    fun init(context: android.content.Context) {
        cacheDir = java.io.File(context.cacheDir, "covers").apply { mkdirs() }
    }

    private fun coverFile(path: String): java.io.File {
        val name = java.security.MessageDigest.getInstance("MD5")
            .digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
        return java.io.File(cacheDir, "$name.jpg")
    }

    /** Pulls the album art embedded inside a local audio file. [fileUri] is a `file://` URI. */
    private suspend fun embeddedArt(fileUri: String, target: Int): Bitmap? = withContext(CoverIo) {
        val path = Uri.parse(fileUri).path ?: return@withContext null
        // Serve a previously-decoded copy from disk instantly; only reach into
        // the audio file when we have never seen this track before.
        val cached = coverFile(path)
        if (cached.isFile) {
            runCatching { decodeFile(cached.absolutePath, target) }.getOrNull()
        } else {
            decodeEmbedded(path)?.let { bytes ->
                runCatching { cached.writeBytes(bytes) }
                decodeScaled(bytes, target)
            }
        }
    }

    /** Reads the embedded album art bytes out of an audio file, or null. */
    private fun decodeEmbedded(path: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    suspend fun bitmapFor(station: Station): Bitmap? {
        if (station.source == StationSource.Cliamp) return null
        bitmaps.get(station.id)?.let { return it }
        if (isOut(station.id)) return null

        val bmp = if (station.source == StationSource.Local) {
            embeddedArt(station.url, TARGET)
        } else {
            cover(station, ::download)
        }
        if (bmp == null) noteMiss(station.id) else bitmaps.put(station.id, bmp)
        return bmp
    }

    /**
     * Low-quality art for tiny surfaces (row thumbnails, the mini player).
     * Decodes at [TARGET_SMALL] and serves its own cache so a 100-row list
     * doesn't hold a dozen full-size bitmaps in memory. Unlike [bitmapFor] it
     * keeps retrying a failed cover on every look, which is how the same
     * station can end up with art in the list while the grid still misses it.
     */
    suspend fun bitmapForSmall(station: Station): Bitmap? {
        if (station.source == StationSource.Cliamp) return null
        smallBitmaps.get(station.id)?.let { return it }
        val bmp = if (station.source == StationSource.Local) {
            embeddedArt(station.url, TARGET_SMALL)
        } else {
            cover(station, ::downloadSmall)
        }
        if (bmp != null) smallBitmaps.put(station.id, bmp)
        return bmp
    }

    /**
     * The station's cover: the og:image on its homepage first, then the
     * favicon the directory recorded. A discovered URL that refuses to decode
     * is forgotten, so the next attempt is never pinned to a dead link and
     * the fallback to the favicon happens on the spot.
     */
    private suspend fun cover(station: Station, fetch: suspend (String) -> Bitmap?): Bitmap? {
        val url = imageUrl(station) ?: return null
        val bmp = fetch(url)
        if (bmp != null) return bmp
        resolved.remove(station.id)
        val fav = station.favicon
        return if (fav.startsWith("http") && fav != url) fetch(fav) else null
    }

    /**
     * Art whose URL we already know, rather than one that has to be discovered
     * from a homepage. Provider covers arrive this way: getCoverArt gives a
     * real, already-signed URL, so none of the og:image guessing applies.
     */
    suspend fun bitmapForUrl(url: String): Bitmap? {
        if (url.isBlank()) return null
        bitmaps.get(url)?.let { return it }
        if (isOut(url)) return null
        val bmp = download(url)
        if (bmp == null) noteMiss(url) else bitmaps.put(url, bmp)
        return bmp
    }

    /** A known URL's low-quality art for tiny surfaces, the [bitmapForSmall]
     * counterpart for art we did not have to discover. Podcast rows use this -
     * the catalogue already knows the artwork URL, so only the decode differs. */
    suspend fun bitmapForUrlSmall(url: String): Bitmap? {
        if (url.isBlank()) return null
        smallBitmaps.get(url)?.let { return it }
        if (isOut(url)) return null
        val bmp = downloadSmall(url)
        if (bmp == null) noteMiss(url) else smallBitmaps.put(url, bmp)
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
                decodeScaled(bytes, TARGET)
            }
        }.getOrNull()
    }

    private suspend fun downloadSmall(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val ct = r.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                if (ct.isNotEmpty() && (!ct.startsWith("image/") || ct in undecodable)) return@use null
                val bytes = r.body.byteStream().readAtMost(MAX_IMAGE) ?: return@use null
                if (bytes.size < 64) return@use null
                decodeScaled(bytes, TARGET_SMALL)
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

    private fun decodeScaled(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** Scaled decode straight from a file on disk (e.g. the archived cover). */
    private fun decodeFile(path: String, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** Tiny access-ordered cache; the platform LruCache is fine but this keeps nulls meaningful. */
class LruCache<K, V>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }
    @Synchronized fun get(key: K): V? = map[key]
    @Synchronized fun put(key: K, value: V) { map[key] = value }
    @Synchronized fun remove(key: K): V? = map.remove(key)
}
