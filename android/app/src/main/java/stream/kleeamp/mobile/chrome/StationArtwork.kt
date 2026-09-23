package stream.kleeamp.mobile.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import stream.kleeamp.mobile.art.ArtResolve
import stream.kleeamp.mobile.art.PlaceholderArt
import stream.kleeamp.mobile.model.Station

/**
 * Single cover-resolution path for every art surface: the memory peek paints
 * on the first frame, the async lookup only ever upgrades to real art. [url]
 * wins outright when set (catalogue art); otherwise the station branches.
 * [deferMs] delays the lookup so fast scrolls never fire it (search rows).
 */
@Composable
fun rememberArt(
    station: Station? = null,
    url: String? = null,
    kind: ArtKind = ArtKind.Thumb,
    deferMs: Long = 0,
): ImageBitmap? {
    val resolver = LocalContext.current.contentResolver
    val key = Triple(station?.id, station?.cover, url ?: station?.url)
    val cached = remember(key, kind) {
        (if (kind == ArtKind.Thumb) ArtResolve.cachedSmall(station, url)
        else ArtResolve.cachedFull(station, url))?.asImageBitmap()
    }
    var art by remember(key, kind) { mutableStateOf(cached) }
    LaunchedEffect(key, kind) {
        if (cached != null) return@LaunchedEffect
        if (deferMs > 0) delay(deferMs)
        val real = (if (kind == ArtKind.Thumb) ArtResolve.small(station, url, resolver)
        else ArtResolve.full(station, url, resolver))?.asImageBitmap()
        if (real != null) art = real
    }
    return art
}

/**
 * Shared small-cover lookup for library and queue rows; decoding uses the
 * bounded cover pool. With [fallback], an item that has no art of its own
 * gets one of the bundled cover designs instead of an empty plate - seeded
 * synchronously from the row-sized cache, so the first frame already shows
 * it and the background lookup only ever upgrades to real art. Local and
 * provider songs never take the fallback (see [Station.bundledCover]).
 */
@Composable
internal fun rememberStationThumbnail(station: Station, fallback: Boolean = true): ImageBitmap? {
    val context = LocalContext.current
    val key = station.id.ifBlank { station.url }
    val state = rememberArt(station = station, kind = ArtKind.Thumb)
    val placeholder = remember(key, fallback) {
        if (fallback && station.bundledCover) PlaceholderArt.thumbnailFor(context, key)?.asImageBitmap() else null
    }
    return state ?: placeholder
}
