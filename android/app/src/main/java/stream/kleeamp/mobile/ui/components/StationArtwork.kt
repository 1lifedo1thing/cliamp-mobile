package stream.kleeamp.mobile.ui.components

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
 * gets one of the bundled cover designs instead of an empty plate.
 */
@Composable
internal fun rememberStationThumbnail(station: Station, fallback: Boolean = true): ImageBitmap? {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var art by remember(station.id, station.cover, station.url) {
        mutableStateOf(
            (LocalArt.cachedSmall(station.cover) ?: StationArtSource.cachedSmall(station))?.asImageBitmap()
        )
    }
    LaunchedEffect(station.id, station.cover, station.url, resolver, fallback) {
        if (art != null) return@LaunchedEffect
        art = (LocalArt.bitmapForSmall(station.cover, resolver)
            ?: StationArtSource.bitmapForKnownSmall(station)
            ?: StationArtSource.bitmapForSmall(station)
            ?: if (fallback) {
                PlaceholderArt.bitmapFor(context, station.id.ifBlank { station.url })
            } else {
                null
            })?.asImageBitmap()
    }
    return art
}
