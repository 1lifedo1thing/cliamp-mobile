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
import stream.kleeamp.mobile.data.LocalArt
import stream.kleeamp.mobile.data.PlaceholderArt
import stream.kleeamp.mobile.data.Station
import stream.kleeamp.mobile.data.StationArtSource

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
    val resolver = context.contentResolver
    val key = station.id.ifBlank { station.url }
    val cached = remember(station.id, station.cover, station.url) {
        (LocalArt.cachedSmall(station.cover) ?: StationArtSource.cachedSmall(station))?.asImageBitmap()
    }
    val placeholder = remember(key) {
        if (fallback && station.bundledCover) PlaceholderArt.thumbnailFor(context, key)?.asImageBitmap() else null
    }
    var art by remember(station.id, station.cover, station.url) { mutableStateOf(cached ?: placeholder) }
    LaunchedEffect(station.id, station.cover, station.url, resolver, fallback) {
        if (cached != null) return@LaunchedEffect
        val real = (LocalArt.bitmapForSmall(station.cover, resolver)
            ?: StationArtSource.bitmapForKnownSmall(station)
            ?: StationArtSource.bitmapForSmall(station))?.asImageBitmap()
        if (real != null) art = real
    }
    return art
}
