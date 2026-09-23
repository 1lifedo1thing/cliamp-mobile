package stream.kleeamp.mobile.art

import android.content.ContentResolver
import android.graphics.Bitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

/**
 * One choke point for cover resolution: the same source branches every art
 * surface uses (local MediaStore, known http cover, scraped discovery), so a
 * row, the mini player and the player screen never disagree on what "the
 * art" for a station is, and prefetch warms exactly the caches rows read.
 */
object ArtResolve {

    /**
     * Small art for rows and the mini player: the same branches
     * [chrome.StationArtwork] and the mini bar resolve, in the same order.
     */
    suspend fun small(station: Station, resolver: ContentResolver): Bitmap? = when {
        station.source == StationSource.Local ->
            LocalArt.bitmapForSmall(station.cover, resolver)
                ?: StationArtSource.bitmapForSmall(station)
        station.cover.startsWith("http") -> StationArtSource.bitmapForKnownSmall(station)
        else -> StationArtSource.bitmapForSmall(station)
    }

    /** Full art for the player screen, same branches as [small] at full size. */
    suspend fun full(station: Station, resolver: ContentResolver): Bitmap? = when {
        station.source == StationSource.Local ->
            LocalArt.bitmapFor(station.cover, resolver)
                ?: StationArtSource.bitmapFor(station)
        station.cover.startsWith("http") -> StationArtSource.bitmapForUrl(station.cover)
        else -> StationArtSource.bitmapFor(station)
    }

    /** Memory-only small peek, safe on Main, for first-frame row paints. */
    fun cachedSmall(station: Station): Bitmap? =
        LocalArt.cachedSmall(station.cover) ?: StationArtSource.cachedSmall(station)

    /** Memory-only full peek, safe on Main, mirroring [full]'s branches. */
    fun cachedFull(station: Station): Bitmap? = when {
        station.source == StationSource.Local ->
            LocalArt.cached(station.cover) ?: StationArtSource.cached(station)
        station.cover.startsWith("http") ->
            StationArtSource.cachedUrl(station.cover) ?: StationArtSource.cached(station)
        else -> StationArtSource.cached(station)
    }

    /**
     * Combined resolve: a known URL wins outright wherever it lives (show
     * artwork, episode art), else the station branches. Matches every row's
     * read order, so prefetch warms exactly what rows paint.
     */
    suspend fun small(station: Station?, url: String?, resolver: ContentResolver): Bitmap? =
        if (!url.isNullOrBlank()) smallUrl(url)
        else station?.let { small(it, resolver) }

    /** Full-size twin of [small]. */
    suspend fun full(station: Station?, url: String?, resolver: ContentResolver): Bitmap? =
        if (!url.isNullOrBlank()) fullUrl(url)
        else station?.let { full(it, resolver) }

    /** Memory-only combined peek, safe on Main, mirroring [small]. */
    fun cachedSmall(station: Station?, url: String?): Bitmap? =
        if (!url.isNullOrBlank()) StationArtSource.cachedSmallUrl(url)
        else station?.let { cachedSmall(it) }

    /** Memory-only combined full peek, safe on Main, mirroring [full]. */
    fun cachedFull(station: Station?, url: String?): Bitmap? =
        if (!url.isNullOrBlank()) StationArtSource.cachedUrl(url)
        else station?.let { cachedFull(it) }

    /** Small art for a known URL (podcast catalogue art), the URL-keyed twin. */
    suspend fun smallUrl(url: String): Bitmap? = StationArtSource.bitmapForUrlSmall(url)

    /** Full art for a known URL. */
    suspend fun fullUrl(url: String): Bitmap? = StationArtSource.bitmapForUrl(url)

    /**
     * Warms small art for the head of a list (the on-screen window plus
     * overscan) so rows compose onto warm memory instead of each firing a
     * cold lookup as they scroll in. Bounded: [limit] items, failures
     * isolated per item, previous runs cancelled by the caller's
     * LaunchedEffect key when the list changes.
     */
    suspend fun prefetchSmall(
        stations: List<Station>,
        resolver: ContentResolver,
        limit: Int = 40,
    ): Unit = supervisorScope {
        stations.asSequence()
            .filter { it.source != StationSource.Cliamp }
            .take(limit)
            .map { s -> async { small(s, resolver) } }
            .toList()
            .awaitAll()
        Unit
    }

    /** URL-keyed twin of [prefetchSmall] for catalogue-art lists. */
    suspend fun prefetchSmallUrls(urls: List<String>, limit: Int = 40): Unit = supervisorScope {
        urls.asSequence()
            .filter { it.isNotBlank() }
            .take(limit)
            .map { u -> async { smallUrl(u) } }
            .toList()
            .awaitAll()
        Unit
    }

    /**
     * Full-size twin for large tiles: warms the bytes on disk, which the
     * small path then decodes without the network. Kept to the first screen
     * of tiles - full decodes are heavy.
     */
    suspend fun prefetchFullUrls(urls: List<String>, limit: Int = 12): Unit = supervisorScope {
        urls.asSequence()
            .filter { it.isNotBlank() }
            .take(limit)
            .map { u -> async { fullUrl(u) } }
            .toList()
            .awaitAll()
        Unit
    }
}
