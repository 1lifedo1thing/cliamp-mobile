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
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.model.Station

/**
 * Single cover-resolution path for every art surface: the memory peek paints
 * on the first frame, the async lookup only ever upgrades to real art. [url]
 * wins outright when set (catalogue art); otherwise the station branches.
 * [deferMs] delays the lookup so fast scrolls never fire it (search rows).
 *
 * Thumbs resolve once: radio rows rely on the StationArtSource miss backoff
 * plus disk/memory cache instead of hammering the network, so list
 * prefetch + rows + scroll never stampede. Full player art keeps two short
 * retries for transient failures.
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
        if (station != null && kind == ArtKind.Thumb &&
            StationArtSource.isMissOut(station.id)
        ) return@LaunchedEffect
        if (deferMs > 0) delay(deferMs)
        if (kind == ArtKind.Thumb) {
            art = ArtResolve.small(station, url, resolver)?.asImageBitmap() ?: art
            return@LaunchedEffect
        }
        repeat(3) { attempt ->
            val real = ArtResolve.full(station, url, resolver)?.asImageBitmap()
            if (real != null) {
                art = real
                return@LaunchedEffect
            }
            if (attempt < 2) delay(2_000)
        }
    }
    return art
}

/**
 * Shared small-cover lookup for library and queue rows; decoding uses the
 * bounded cover pool. Returns real art or null - callers render a generated
 * [stream.kleeamp.mobile.art.SeedPlate] for the null case, seeded
 * synchronously from the row, so the first frame already shows a plate and
 * the background lookup only ever upgrades to real art.
 */
@Composable
internal fun rememberStationThumbnail(station: Station): ImageBitmap? =
    rememberArt(station = station, kind = ArtKind.Thumb)
