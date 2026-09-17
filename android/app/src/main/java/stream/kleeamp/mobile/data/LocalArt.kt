package stream.kleeamp.mobile.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.withContext

/**
 * Artwork for local songs. Unlike radio streams, local files have real covers
 * in the MediaStore (usually the embedded album art rounded up into
 * `content://media/external/audio/albumart`), so we read that directly instead
 * of scraping a homepage.
 *
 * [Station.cover] holds the URI to draw from; for a local song it is the album
 * art, for a playlist it is the cover the user chose (itself likely an album
 * art URI or a picked content URI). Bandwidth is not a concern here, so no
 * network path is involved at all.
 */
object LocalArt {

    private const val TARGET = 512
    private const val TARGET_SMALL = 96
    private val bitmaps = LruCache<String, Bitmap>(96)
    private val smallBitmaps = LruCache<String, Bitmap>(384)
    private val misses = LruCache<String, Boolean>(128)

    suspend fun bitmapFor(cover: String?, resolver: ContentResolver): Bitmap? =
        bitmapForAt(cover, resolver, TARGET, bitmaps)

    /** Low-quality art for row thumbnails and the mini player. */
    suspend fun bitmapForSmall(cover: String?, resolver: ContentResolver): Bitmap? =
        bitmapForAt(cover, resolver, TARGET_SMALL, smallBitmaps)

    /**
     * Memory-only peek for rows: a plain LRU get, safe on Main, so a
     * scrolling list can paint its cached cover synchronously instead of
     * flashing the placeholder through an async lookup that hits memory
     * a frame later anyway.
     */
    fun cachedSmall(cover: String?): Bitmap? =
        cover?.takeIf { it.isNotBlank() }?.let { smallBitmaps.get(it) }

    private suspend fun bitmapForAt(
        cover: String?,
        resolver: ContentResolver,
        target: Int,
        cache: LruCache<String, Bitmap>,
    ): Bitmap? {
        if (cover.isNullOrBlank()) return null
        cache.get(cover)?.let { return it }
        if (misses.get(cover) == true) return null
        val bmp = decode(cover, resolver, target)
        if (bmp == null) misses.put(cover, true) else cache.put(cover, bmp)
        return bmp
    }

    private suspend fun decode(cover: String, resolver: ContentResolver, target: Int): Bitmap? =
        withContext(CoverIo) {
            runCatching {
                val uri = Uri.parse(cover)
                if (uri.scheme == "file") {
                    // Local-song covers are plain on-disk files; ContentResolver
                    // cannot open file:// URIs, so decode the path directly.
                    decodeFile(uri.path, target)
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                    val sample = sampleFor(bounds.outWidth, bounds.outHeight, target)
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    resolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                }
            }.getOrNull()
        }

    private fun decodeFile(path: String?, target: Int): Bitmap? {
        if (path.isNullOrBlank()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, target) }
        return BitmapFactory.decodeFile(path, opts)
    }

    internal fun sampleFor(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) {
            sample *= 2
        }
        return sample
    }
}