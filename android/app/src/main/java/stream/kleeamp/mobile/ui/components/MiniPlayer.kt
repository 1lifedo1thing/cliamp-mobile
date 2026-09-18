package stream.kleeamp.mobile.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.data.LocalArt
import stream.kleeamp.mobile.data.Station
import stream.kleeamp.mobile.data.StationArtSource
import stream.kleeamp.mobile.data.StationSource
import stream.kleeamp.mobile.data.sourceLine
import stream.kleeamp.mobile.ui.theme.KleeampShape
import stream.kleeamp.mobile.ui.theme.KleeampType
import stream.kleeamp.mobile.ui.theme.LocalPalette
import stream.kleeamp.mobile.ui.theme.Mono

/** The lockscreen widget's in-app twin: art plate, meter, one key. */
@Composable
fun MiniPlayer(
    station: Station?,
    streamTitle: String,
    playing: Boolean,
    buffering: Boolean,
    reconnecting: Int = 0,
    visualizer: String = "spectrum",
    hasPrev: Boolean = false,
    hasNext: Boolean = false,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    onOpenUpNext: () -> Unit,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val p = LocalPalette.current
    // The bar never goes away: it shows the current or last-played station,
    // or the empty "nothing playing" state when nothing has played yet.
    val empty = station == null
    // The meter is the visualizer; when the setting is off the frame loop is
    // not run at all, so the brick meter is truly gone from the bar.
    val frame = if (visualizer != "off")
        rememberMeter(columns = MeterSize.Mini.columns, live = playing)
    else null

    Column(Modifier.fillMaxWidth().background(p.panel)) {
        HairlineDivider(region = true)
        Row(
            Modifier
                .fillMaxWidth()
                .microPress(onClick = onOpen)
                .padding(horizontal = Gutter, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MiniArt(
                station = station,
                frame = frame,
            )
            if (empty) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Mono("nothing playing", KleeampType.rowPrimaryMedium, p.ink, maxLines = 1)
                    Mono("pick a station to start", KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
                }
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Mono(station.name, KleeampType.rowPrimaryMedium, p.ink, maxLines = 1)
                    Mono(
                        when {
                            reconnecting > 0 -> "reconnecting · $reconnecting"
                            buffering -> "buffering…"
                            streamTitle.isNotBlank() -> streamTitle
                            else -> station.sourceLine
                        },
                        KleeampType.rowSecondary,
                        if (buffering || reconnecting > 0) p.amber else p.inkTertiary,
                        maxLines = 1,
                    )
                }
            }
            // Queue sits before the transport cluster on the right edge.
            Icon(
                KleeampIcons.UpNextTabLines,
                "up next",
                Modifier
                    .size(16.dp)
                    .microPress(onClick = onOpenUpNext),
                tint = p.ink,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MiniKey(
                    icon = KleeampIcons.Prev,
                    label = "previous",
                    enabled = hasPrev,
                    onClick = onPrev,
                )
                Box(
                    Modifier
                        .size(38.dp)
                        .microPress(onClick = onToggle)
                        .clip(RoundedCornerShape(KleeampShape.medium))
                        .background(if (p.dark) p.accent else p.ink),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) KleeampIcons.Pause else KleeampIcons.PlayTab,
                        if (playing) "pause" else "play",
                        Modifier.size(if (playing) 13.dp else 15.dp),
                        tint = if (p.dark) p.onAccent else p.ground,
                    )
                }
                MiniKey(
                    icon = KleeampIcons.Next,
                    label = "next",
                    enabled = hasNext,
                    onClick = onNext,
                )
            }
        }
    }
}

/** A small prev/next key for the mini bar transport cluster. */
@Composable
private fun MiniKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .background(p.keyFace)
            .border(1.dp, p.keyBorder, RoundedCornerShape(KleeampShape.small))
            .microPress(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            label,
            Modifier.size(width = 14.dp, height = 11.dp),
            tint = if (enabled) p.ink else p.inkFaint,
        )
    }
}

/**
 * The mini bar's leading slot: a small square cover thumbnail for anything
 * that has art - local embedded art, a provider/podcast cover, or scraped
 * radio branding (og:image, touch icon, favicon). Only when no cover exists
 * does it fall back to the brick meter, and that meter only when the
 * visualizer is on; with it off the striped placeholder plate is shown.
 */
/** Synchronous memory peek behind [MiniArt], mirroring its async resolve. The
 * full-size caches are included: the notification path warms those, and the
 * thumbnail downscales them for free - still the new item on frame one. */
private fun peekSmall(station: Station?): androidx.compose.ui.graphics.ImageBitmap? {
    if (station == null) return null
    val bmp = when {
        station.source == StationSource.Local ->
            LocalArt.cachedSmall(station.cover)
                ?: LocalArt.cached(station.cover)
                ?: StationArtSource.cachedSmall(station)
                ?: StationArtSource.cached(station)
        station.cover.startsWith("http") ->
            StationArtSource.cachedUrl(station.cover)
                ?: StationArtSource.cachedSmallUrl(station.cover)
                ?: StationArtSource.cachedSmall(station)
        else ->
            StationArtSource.cachedSmall(station)
                ?: StationArtSource.cached(station)
    }
    return bmp?.asImageBitmap()
}

@Composable
private fun MiniArt(station: Station?, frame: MeterFrame?) {
    val p = LocalPalette.current
    val context = LocalContext.current
    // Same synchronous-first treatment as the player screen: paint a known
    // cover on the first frame instead of flashing the plate on every change.
    var art by remember(station?.id) { mutableStateOf(peekSmall(station)) }
    LaunchedEffect(station?.id) {
        if (art != null) return@LaunchedEffect
        val s = station ?: return@LaunchedEffect
        art = when {
            s.source == StationSource.Local ->
                LocalArt.bitmapForSmall(s.cover, context.contentResolver)
                    ?: StationArtSource.bitmapForSmall(s) // else embedded album art
            s.cover.startsWith("http") -> StationArtSource.bitmapForUrl(s.cover)
            // Radio streams carry no cover of their own, so the best available
            // branding is scraped: og:image, then apple-touch-icon, then the
            // directory's favicon. Cliamp channels return null here.
            else -> StationArtSource.bitmapForSmall(s)
        }?.asImageBitmap()
    }
    val a = art
    if (a != null) {
        // Real cover art gets a square thumbnail so the plate reads as a little
        // album square; the brick meter fallback below stays squat instead.
        Image(
            bitmap = a,
            contentDescription = station?.name,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(KleeampShape.medium)),
            contentScale = ContentScale.Crop,
        )
    } else if (frame != null) {
        BrickMeter(
            frame = frame,
            modifier = Modifier.size(width = 40.dp, height = MeterSize.Mini.height),
            brick = MeterSize.Mini.brick,
            gap = MeterSize.Mini.gap,
            columnGap = 2.dp,
            showPeaks = false,
        )
    } else {
        ArtPlate(
            modifier = Modifier.size(40.dp),
            radius = KleeampShape.medium,
        )
    }
}
