package stream.kleeamp.mobile.art

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import stream.kleeamp.mobile.net.Http
import java.net.URI
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

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
// Single art-source choke point; one small function per source and size.
@Suppress("TooManyFunctions")
object StationArtSource {

    // og:image and friends live in <head>, virtually always within the
    // first kilobytes: waiting on more of a slow homepage only delays art
    // that is already past us.
    private const val MAX_HTML = 24 * 1024
    private const val MAX_IMAGE = 4 * 1024 * 1024
    // Full art feeds the ~900px hero plate: a 512px decode upscaled that
    // far reads soft, so full decodes target 1024 instead. Disk keeps the
    // original bytes, so this takes effect without re-downloading.
    private const val TARGET = 1024

    /** Thumbnails (row icons, the mini player) never need full detail. */
    private const val TARGET_SMALL = 96

    /** A stored cover is trusted this long before the network is asked again. */
    private const val DISK_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** Cap on files in covers/: ~500 small covers weigh a few tens of MB. */
    private const val MAX_DISK_FILES = 500

    /** Formats BitmapFactory cannot decode, however cheerfully they are served.
     * ICO is served as image/x-icon or image/vnd.microsoft.icon and decodes
     * fine, so only SVG stays on the block list. */
    private val undecodable = setOf("image/svg+xml")

    private val resolved = LruCache<String, String>(128)
    // Bitmap caches are byte-budgeted, not count-bounded: a 1024px bitmap is
    // four megabytes, so the old 128-count cap could hold ~512 MB. Size is in KB.
    private val bitmaps = object : android.util.LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val smallBitmaps = object : android.util.LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = (value.byteCount / 1024).coerceAtLeast(1)
    }
    // Key -> when its art last failed. A failure is not permanent: a cover
    // that times out on a cold-start stampede still gets another try once the
    // backoff passes, so a station that does have art ends up showing it.
    private val missedAt = LruCache<String, Long>(256)

    /** How long a failed cover is left alone before it is tried again. */
    private const val MISS_RETRY_MS = 60_000L

    private fun noteMiss(key: String) = missedAt.put(key, System.currentTimeMillis())
    private fun clearMiss(key: String) { missedAt.remove(key) }
    private fun isOut(key: String): Boolean =
        missedAt.get(key)?.let { System.currentTimeMillis() - it < MISS_RETRY_MS } ?: false

    /** Public guard so rows skip even the disk peek while a station rests. */
    fun isMissOut(key: String): Boolean = isOut(key)

    /**
     * Direct image URLs to try before any homepage scrape, in order. Pure
     * string math (no I/O) so JVM unit tests can pin the pick order: a known
     * cover first, then the directory favicon. The homepage is deliberately
     * not a candidate - it is a page to scrape only after every direct image
     * has failed. SVG never decodes, so it is skipped without a network try.
     */
    fun artCandidates(cover: String, favicon: String, homepage: String): List<String> {
        val out = ArrayList<String>(2)
        if (cover.startsWith("http") && !isSvgUrl(cover)) out.add(cover)
        if (favicon.startsWith("http") && !isSvgUrl(favicon) && favicon !in out) out.add(favicon)
        return out
    }

    /** True when the URL points at an SVG BitmapFactory can never decode. */
    fun isSvgUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".svg")
    }

    /** Directory holding decoded embedded-art bytes, keyed by audio path hash. */
    private var cacheDir: java.io.File? = null

    // Persisted winning image URL: memory `resolved` dies with the process,
    // so the winner is also filed next to covers/ as <md5(id)>.txt holding
    // "url\ntimestamp". Next launch serves disk bytes or re-downloads that
    // URL without ever scraping. TTL matches DISK_TTL_MS.
    private fun resolvedFile(id: String): java.io.File? {
        val base = cacheDir ?: return null
        val name = runCatching {
            java.security.MessageDigest.getInstance("MD5")
                .digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return null
        return java.io.File(java.io.File(base, "resolved").apply { mkdirs() }, "$name.txt")
    }

    private fun readResolvedDisk(id: String): String? {
        val f = resolvedFile(id) ?: return null
        if (!f.isFile) return null
        val lines = runCatching { f.readLines() }.getOrNull() ?: return null
        if (lines.isEmpty()) return null
        val url = lines[0].trim()
        val at = lines.getOrNull(1)?.toLongOrNull() ?: f.lastModified()
        if (url.isBlank() || !url.startsWith("http")) {
            runCatching { f.delete() }
            return null
        }
        if (System.currentTimeMillis() - at > DISK_TTL_MS) {
            runCatching { f.delete() }
            return null
        }
        return url
    }

    private fun writeResolved(id: String, url: String) {
        resolved.put(id, url)
        val f = resolvedFile(id) ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText("$url\n${System.currentTimeMillis()}")
        }
    }

    private fun dropResolved(id: String) {
        resolved.remove(id)
        runCatching { resolvedFile(id)?.delete() }
    }

    /** Call once at startup with an application context. */
    fun init(context: android.content.Context) {
        cacheDir = java.io.File(context.cacheDir, "covers").apply { mkdirs() }
    }

    /**
     * Split slow-lane / fast-lane for cover traffic. A coverless station
     * costs two sequential roundtrips (homepage scrape, then image), and a
     * list prefetch fires dozens of stations at once through one 3-wide
     * pool: every visible row queued behind 40 scrapes. Scrapes keep a
     * narrow lane so dead homepages cannot crowd out anything; downloads -
     * single roundtrips, usually CDN-fast - get the wide lane, so a row's
     * image never waits on another station's HTML.
     */
    private val ArtScrape = Dispatchers.IO.limitedParallelism(2)
    private val ArtFetch = Dispatchers.IO.limitedParallelism(6)

    /**
     * In-flight fetch coalescing. Prefetch storms and per-row lookups ask
     * for the same station at the same time; without this every duplicate
     * burns its own scrape plus download on the pools above. Concurrent
     * callers for one key share the winner's result; sequential callers
     * (cache hits, retries) never touch the map.
     */
    private val flightMutex = Mutex()
    private val flights = HashMap<String, Deferred<Any?>>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> coalesced(key: String, work: suspend () -> T): T {
        val mine = CompletableDeferred<Any?>()
        val active = flightMutex.withLock {
            flights[key] ?: run { flights[key] = mine; null }
        }
        if (active != null) return active.await() as T
        // runCatching rather than a try/catch: completing the box on every
        // path (cancellation included) is what keeps joiners from hanging.
        return runCatching { work() }
            .onSuccess { mine.complete(it) }
            .onFailure { mine.completeExceptionally(it) }
            .also { flightMutex.withLock { if (flights[key] === mine) flights.remove(key) } }
            .getOrThrow()
    }

    /** Drop decoded bitmaps under memory pressure; disk + network re-serve. */
    fun onTrimMemory(level: Int) {
        // ComponentCallbacks2.TRIM_MEMORY_MODERATE (60) by value: the
        // constant itself is deprecated in recent SDKs.
        if (level >= 60) {
            bitmaps.evictAll()
            smallBitmaps.evictAll()
        }
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

        // Disk on the capped pool; the network below must not hold a pool
        // slot across its suspends, or a few slow scrapes starve every other
        // decode - including the player art - behind them.
        val bmp = if (station.source == StationSource.Local) {
            embeddedArt(station.url, TARGET)
        } else {
            withContext(CoverIo) { disk(station.id, TARGET) }
                ?: cover(station) { url, save -> download(url, save) }
        }
        if (bmp == null) noteMiss(station.id) else bitmaps.put(station.id, bmp)
        return bmp
    }

    /**
     * Full-size hero art for the player: homepage og:image first, then the
     * direct favicon/cover. The inverse of the thumb path on purpose - a
     * tiny but decodable favicon must not pin the ~900px hero when a large
     * og:image exists on the homepage. List rows never use this; only the
     * player hero and the bounded hero warm do.
     *
     * Shares the id miss backoff with the thumb path so a dead homepage
     * rests for both together, and coalesces per station so concurrent
     * player + warm lookups share one job. Disk bytes are shared with the
     * thumb path (original bytes, decoded per target), including URL-keyed
     * files left by earlier builds.
     */
    suspend fun bitmapForHero(station: Station): Bitmap? {
        if (station.source == StationSource.Cliamp) return null
        bitmaps.get(station.id)?.let { return it }
        if (isOut(station.id)) return null
        if (station.source == StationSource.Local) return bitmapFor(station)
        return coalesced("hero:${station.id}") {
            bitmaps.get(station.id)?.let { return@coalesced it }
            if (isOut(station.id)) return@coalesced null
            val candidates = artCandidates(station.cover, station.favicon, station.homepage)
            withContext(CoverIo) {
                (listOf(station.id) + candidates).distinct()
                    .firstNotNullOfOrNull { key ->
                        disk(key, TARGET)?.let { key to it }
                    }
            }?.let { (key, bmp) ->
                withContext(CoverIo) { ensureDiskCopy(key, station.id) }
                bitmaps.put(station.id, bmp)
                clearMiss(station.id)
                return@coalesced bmp
            }
            // Scrape-first: og:image is usually the large brand mark; the
            // direct favicon/cover is the fallback, not the prize.
            val bmp = cover(station) { url, save -> download(url, save) }
                ?: candidates.firstNotNullOfOrNull { url -> download(url, save = station.id) }
            if (bmp == null) noteMiss(station.id) else {
                bitmaps.put(station.id, bmp)
                clearMiss(station.id)
            }
            bmp
        }
    }

    /**
     * Low-quality art for tiny surfaces (row icons, the mini player).
     * Decodes at [TARGET_SMALL] and serves its own cache so a 100-row list
     * doesn't hold a dozen full-size bitmaps in memory. Failed covers rest
     * for [MISS_RETRY_MS] whatever the source, so scroll storms never
     * re-hit the network.
     *
     * Fast path: direct favicon/cover downloads on ArtFetch first, homepage
     * scrape only when every direct image failed. The whole station job is
     * one coalesced unit so prefetch + rows + scroll share it.
     */
    suspend fun bitmapForSmall(station: Station): Bitmap? {
        if (station.source == StationSource.Cliamp) return null
        smallBitmaps.get(station.id)?.let { return it }
        peekSmallUrl(station)?.let { return fileUnderId(station.id, it) }
        if (isOut(station.id)) return null
        if (station.source == StationSource.Local) {
            val bmp = embeddedArt(station.url, TARGET_SMALL)
            if (bmp != null) { clearMiss(station.id); smallBitmaps.put(station.id, bmp) }
            else noteMiss(station.id)
            return bmp
        }
        return coalesced("small:${station.id}") { smallCoalesced(station) }
    }

    /** Memory peek over the direct URL lanes (cover, favicon, resolved). */
    private fun peekSmallUrl(station: Station): Bitmap? {
        station.cover.takeIf { it.startsWith("http") }?.let { smallBitmaps.get(it)?.let { return it } }
        station.favicon.takeIf { it.startsWith("http") }?.let { smallBitmaps.get(it)?.let { return it } }
        resolved.get(station.id)?.let { smallBitmaps.get(it)?.let { return it } }
        return null
    }

    /**
     * The coalesced thumb worker: disk (id + URL keys), then direct
     * downloads, then the persisted winner URL, then homepage scrape last.
     * Disk lives on CoverIo; scrape/download suspend off it.
     */
    private suspend fun smallCoalesced(station: Station): Bitmap? {
        smallBitmaps.get(station.id)?.let { return it }
        peekSmallUrl(station)?.let { return fileUnderId(station.id, it) }
        if (isOut(station.id)) return null

        val candidates = artCandidates(station.cover, station.favicon, station.homepage)
        val winnerDisk = resolved.get(station.id) ?: withContext(CoverIo) { readResolvedDisk(station.id) }
        if (winnerDisk != null) resolved.put(station.id, winnerDisk)
        val diskKeys = buildList {
            add(station.id)
            winnerDisk?.let { add(it) }
            candidates.forEach { add(it) }
        }.distinct()

        val diskHit = withContext(CoverIo) {
            diskKeys.firstNotNullOfOrNull { key ->
                disk(key, TARGET_SMALL)?.let { key to it }
            }
        }
        if (diskHit != null) {
            val (key, bmp) = diskHit
            withContext(CoverIo) {
                ensureDiskCopy(key, station.id)
                if (key != station.id) ensureDiskCopy(station.id, key)
            }
            winnerDisk?.let { writeResolved(station.id, it) }
            if (key.startsWith("http")) writeResolved(station.id, key)
            clearMiss(station.id)
            smallBitmaps.put(station.id, bmp)
            if (key.startsWith("http")) smallBitmaps.put(key, bmp)
            return bmp
        }

        for (url in candidates) {
            val bmp = download(url, save = station.id, target = TARGET_SMALL) ?: continue
            onSmallSuccess(station.id, url, bmp)
            return bmp
        }

        if (winnerDisk != null && winnerDisk !in candidates) {
            val bmp = download(winnerDisk, save = station.id, target = TARGET_SMALL)
            if (bmp != null) {
                onSmallSuccess(station.id, winnerDisk, bmp)
                return bmp
            }
            dropResolved(station.id)
        }

        val page = station.homepage.takeIf { it.startsWith("http") }
        if (page != null) {
            val scraped = coalesced("page:${station.id}") { scrape(page) }
            if (scraped != null && !isSvgUrl(scraped)) {
                val bmp = download(scraped, save = station.id, target = TARGET_SMALL)
                if (bmp != null) {
                    onSmallSuccess(station.id, scraped, bmp)
                    return bmp
                }
                if (scraped == resolved.get(station.id)) dropResolved(station.id)
            }
        }
        noteMiss(station.id)
        return null
    }

    /** Warm id + URL memory keys, mirror disk bytes, persist the winner. */
    private suspend fun onSmallSuccess(id: String, url: String, bmp: Bitmap) {
        smallBitmaps.put(id, bmp)
        if (url.startsWith("http")) smallBitmaps.put(url, bmp)
        withContext(CoverIo) { ensureDiskCopy(id, url) }
        writeResolved(id, url)
        clearMiss(id)
    }

    /** Copy cover bytes from [fromKey] to [toKey] when the target is missing. */
    private fun ensureDiskCopy(fromKey: String, toKey: String) {
        if (fromKey == toKey || cacheDir == null) return
        runCatching {
            val src = coverFile(fromKey)
            val dst = coverFile(toKey)
            if (src.absolutePath != dst.absolutePath && src.isFile &&
                (!dst.isFile || dst.length() == 0L)
            ) {
                src.copyTo(dst, overwrite = true)
            }
        }
    }

    /**
     * The station's full-size cover for the player: the persisted winner or
     * homepage og:image first, then the directory favicon. A discovered URL
     * that refuses to decode is forgotten, so the next attempt is never
     * pinned to a dead link and the fallback to the favicon happens on the
     * spot. Anything that decodes is written to disk under [station]'s id,
     * so a later launch reads it back without the network. List rows never
     * use this path - they stay on the favicon-first thumb above.
     */
    private suspend fun cover(station: Station, fetch: suspend (String, String?) -> Bitmap?): Bitmap? {
        val url = imageUrl(station) ?: return null
        val bmp = fetch(url, station.id)
        if (bmp != null) return bmp
        dropResolved(station.id)
        val fav = station.favicon
        return if (fav.startsWith("http") && fav != url && !isSvgUrl(fav)) fetch(fav, station.id) else null
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
        val bmp = withContext(CoverIo) { disk(url, TARGET) } ?: download(url, save = url)
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
        return withContext(CoverIo) {
            val bmp = disk(url, TARGET_SMALL) ?: download(url, save = url, target = TARGET_SMALL)
            if (bmp == null) noteMiss(url) else smallBitmaps.put(url, bmp)
            bmp
        }
    }

    /**
     * Memory-only peeks for rows: plain LRU gets, safe on Main, so a
     * scrolling list paints cached covers synchronously instead of flashing
     * placeholders through an async lookup that would hit memory a frame
     * later anyway. Known art and discovery share the station-id key; the
     * URL-keyed form is for callers that only ever held a URL.
     */
    fun cachedSmall(station: Station): Bitmap? =
        // Stable cover URLs are warmed under the URL key by rows and
        // prefetch; check it before the id key so a fresh surface (menu,
        // mini player) paints from memory on its first frame. Rotating
        // signed URLs miss here and fall through to the id key as before.
        // Favicon and resolved lanes join so a thumb warmed under any key
        // paints on the first frame.
        station.cover.takeIf { it.startsWith("http") }?.let { smallBitmaps.get(it) }
            ?: station.favicon.takeIf { it.startsWith("http") }?.let { smallBitmaps.get(it) }
            ?: resolved.get(station.id)?.let { smallBitmaps.get(it) }
            ?: smallBitmaps.get(station.id)

    /** Memory-only peek for full-size art, the [bitmapFor] counterpart of
     * [cachedSmall]: a plain LRU get, safe on Main, so the player screen can
     * paint a known cover synchronously on a song change instead of flashing
     * the empty plate through an async lookup that hits memory anyway. */
    fun cached(station: Station): Bitmap? = bitmaps.get(station.id)

    /** Full-size version of [cachedSmallUrl] for callers holding only a URL. */
    fun cachedUrl(url: String): Bitmap? =
        url.takeIf { it.isNotBlank() }?.let { bitmaps.get(it) }

    fun cachedSmallUrl(url: String): Bitmap? =
        url.takeIf { it.isNotBlank() }?.let { smallBitmaps.get(it) }

    /**
     * Test-only: seeds the small memory lane under [key], the way a warmed
     * row or prefetch leaves it. Lets device tests prove one lane reads
     * another's bytes without touching the network.
     */
    @androidx.annotation.VisibleForTesting
    internal fun seedSmallForTest(key: String, bmp: Bitmap) {
        smallBitmaps.put(key, bmp)
    }

    /** Test-only: drops a seeded key so device tests never leak into each other. */
    @androidx.annotation.VisibleForTesting
    internal fun dropSmallForTest(key: String) {
        smallBitmaps.remove(key)
    }

    /** Test-only: force a miss timestamp so backoff branches stay JVM-testable. */
    @androidx.annotation.VisibleForTesting
    internal fun seedMissForTest(key: String, atMs: Long = System.currentTimeMillis()) {
        missedAt.put(key, atMs)
    }

    /** Test-only: clear miss state between JVM tests. */
    @androidx.annotation.VisibleForTesting
    internal fun clearMissForTest(key: String) {
        missedAt.remove(key)
    }

    /**
     * A known cover URL cached the stations way: keyed by the stable station
     * id rather than the URL. Provider artwork URLs are signed per request,
     * so keying by URL (as [bitmapForUrlSmall] does) never hits twice and
     * every list build re-downloads every cover. The bytes on disk are shared
     * with the discovery path, which files under the same id.
     *
     * The URL lane is consulted first from memory and disk: rows and prefetch
     * warm exactly those keys, so a menu opening onto an already-listed
     * episode paints instantly instead of firing a duplicate cold download
     * under the id key. A URL hit is also filed under the id, so the
     * id-keyed peek hits from then on. Rotating signed URLs simply miss both
     * URL checks and fall through to the id lane as before.
     *
     * Directory/Custom stations share the favicon-first coalesced thumb job
     * with [bitmapForSmall] (same "small:id" key), so prefetch and rows never
     * run duplicate downloads and a dead favicon still falls back to the
     * homepage scrape inside [smallCoalesced]. Provider/Podcast art has no
     * homepage and stays scrape-free.
     */
    suspend fun bitmapForKnownSmall(station: Station): Bitmap? {
        val url = station.cover.takeIf { it.startsWith("http") } ?: return bitmapForSmall(station)
        if (station.source == StationSource.Directory || station.source == StationSource.Custom) {
            return bitmapForSmall(station)
        }
        smallBitmaps.get(url)?.let { return fileUnderId(station.id, it) }
        val fromDisk = withContext(CoverIo) { disk(url, TARGET_SMALL) }
        if (fromDisk != null) {
            withContext(CoverIo) { ensureDiskCopy(url, station.id) }
            return fileUnderId(station.id, fromDisk)
        }
        smallBitmaps.get(station.id)?.let { return it }
        if (isOut(station.id)) return null
        val bmp = withContext(CoverIo) { disk(station.id, TARGET_SMALL) }
            ?: download(url, save = station.id, target = TARGET_SMALL)
        if (bmp == null) noteMiss(station.id) else {
            smallBitmaps.put(station.id, bmp)
            smallBitmaps.put(url, bmp)
            withContext(CoverIo) { ensureDiskCopy(station.id, url) }
            clearMiss(station.id)
        }
        return bmp
    }

    /** Files a URL-lane hit under the station id so id-keyed peeks hit too. */
    private fun fileUnderId(id: String, bmp: Bitmap): Bitmap {
        smallBitmaps.put(id, bmp)
        return bmp
    }

    private suspend fun imageUrl(station: Station): String? {
        resolved.get(station.id)?.let { return it }
        withContext(CoverIo) { readResolvedDisk(station.id) }?.let {
            resolved.put(station.id, it)
            return it
        }
        val fromPage = station.homepage.takeIf { it.startsWith("http") }?.let { page ->
            coalesced("page:${station.id}") { scrape(page) }
        }
        val candidate = fromPage ?: station.favicon.takeIf { it.startsWith("http") && !isSvgUrl(it) }
        if (candidate != null) writeResolved(station.id, candidate)
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

    private suspend fun scrape(homepage: String): String? = withContext(ArtScrape) {
        runCatching {
            val req = Request.Builder().url(homepage)
                .header("User-Agent", Http.USER_AGENT)
                .header("Accept", "text/html")
                .build()
            Http.artClient.newCall(req).execute().use { r ->
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

    private suspend fun download(
        url: String,
        save: String? = null,
        target: Int = TARGET,
    ): Bitmap? = coalesced("dl:$url@$target") {
        withContext(ArtFetch) {
            runCatching {
                val req = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
                Http.artClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val ct = r.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                    if (ct.isNotEmpty() && (!ct.startsWith("image/") || ct in undecodable)) return@use null
                    val bytes = r.body.byteStream().readAtMost(MAX_IMAGE) ?: return@use null
                    if (bytes.size < 64) return@use null
                    save?.let { runCatching { coverFile(it).writeBytes(bytes) } }
                    decodeScaled(bytes, target)
                }
            }.getOrNull()
        }
    }

    /**
     * A cover stored on a previous launch, decoded at [target]. Returns null
     * when there is nothing on disk or it is older than [DISK_TTL_MS], in
     * which case the caller refetches; a stale station's icon changing should
     * eventually win over an ever-fresher copy of the old one.
     */
    private fun disk(key: String, target: Int): Bitmap? {
        val f = coverFile(key)
        if (!f.isFile) return null
        if (System.currentTimeMillis() - f.lastModified() > DISK_TTL_MS) return null
        return runCatching { decodeFile(f.absolutePath, target) }.getOrNull()
    }

    /**
     * Delete what `disk` would reject anyway (stale) plus anything past a
     * file cap, oldest first. Nothing pruned this directory before: the TTL
     * only applied on read, so it grew forever. The resolved-URL sidecars
     * prune under the same TTL.
     */
    suspend fun pruneDisk() = withContext(CoverIo) {
        val dir = cacheDir ?: return@withContext
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { f ->
            if (f.isFile && now - f.lastModified() > DISK_TTL_MS) runCatching { f.delete() }
        }
        dir.listFiles { f -> f.isFile }
            ?.sortedBy { it.lastModified() }
            ?.dropLast(MAX_DISK_FILES)
            ?.forEach { runCatching { it.delete() } }
        runCatching {
            java.io.File(dir, "resolved").listFiles()?.forEach { f ->
                if (now - f.lastModified() > DISK_TTL_MS) runCatching { f.delete() }
            }
        }
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
        val opts = BitmapFactory.Options().apply {
            inSampleSize = LocalArt.sampleFor(bounds.outWidth, bounds.outHeight, target)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** Scaled decode straight from a file on disk (e.g. the archived cover). */
    private fun decodeFile(path: String, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = LocalArt.sampleFor(bounds.outWidth, bounds.outHeight, target)
        })
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
