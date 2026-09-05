package stream.cliamp.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
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
import stream.cliamp.mobile.data.LocalArt
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationArtSource
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.ui.components.BrickMeter
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.MeterFrame
import stream.cliamp.mobile.ui.components.MeterSize
import stream.cliamp.mobile.ui.components.StripedArt
import stream.cliamp.mobile.ui.components.rememberMeter
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

/** The lockscreen widget's in-app twin: art plate, meter, one key. */
@Composable
fun MiniPlayer(
    station: Station?,
    streamTitle: String,
    playing: Boolean,
    buffering: Boolean,
    reconnecting: Int = 0,
    queueCount: Int,
    visualizer: String = "spectrum",
    hasPrev: Boolean = false,
    hasNext: Boolean = false,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    onOpenQueue: () -> Unit,
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
                .clickable(onClick = onOpen)
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
                    Mono("nothing playing", CliampType.rowPrimaryMedium, p.ink, maxLines = 1)
                    Mono("pick a station to start", CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
                }
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Mono(station!!.name, CliampType.rowPrimaryMedium, p.ink, maxLines = 1)
                    Mono(
                        when {
                            reconnecting > 0 -> "reconnecting · $reconnecting"
                            buffering -> "buffering…"
                            streamTitle.isNotBlank() -> streamTitle
                            station!!.source == StationSource.Cliamp -> "cliamp radio"
                            station!!.source == StationSource.Local ->
                                station!!.artistAlbum.ifBlank { "local audio" }
                            station!!.source == StationSource.Podcast ->
                                station!!.artist.ifBlank { "podcast" }
                            else -> station!!.meta.ifBlank { "live stream" }
                        },
                        CliampType.rowSecondary,
                        if (buffering || reconnecting > 0) p.amber else p.inkTertiary,
                        maxLines = 1,
                    )
                }
            }
            // Queue sits before the transport cluster on the right edge.
            Icon(
                CliampIcons.QueueTabLines,
                "queue",
                Modifier
                    .size(16.dp)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onOpenQueue),
                tint = p.ink,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MiniKey(
                    icon = CliampIcons.Prev,
                    label = "previous",
                    enabled = hasPrev,
                    onClick = onPrev,
                )
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (p.dark) p.accent else p.ink)
                        .clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) CliampIcons.Pause else CliampIcons.PlayTab,
                        if (playing) "pause" else "play",
                        Modifier.size(if (playing) 13.dp else 15.dp),
                        tint = if (p.dark) p.onAccent else p.ground,
                    )
                }
                MiniKey(
                    icon = CliampIcons.Next,
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
            .clip(RoundedCornerShape(7.dp))
            .background(p.keyFace)
            .border(1.dp, p.keyBorder, RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick),
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
@Composable
private fun MiniArt(station: Station?, frame: MeterFrame?) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var art by remember(station?.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(station?.id) {
        art = null
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
    if (art != null) {
        // Real cover art gets a square thumbnail so the plate reads as a little
        // album square; the brick meter fallback below stays squat instead.
        Image(
            bitmap = art!!,
            contentDescription = station?.name,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp)),
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
        StripedArt(
            modifier = Modifier.size(40.dp),
            radius = 8.dp,
        )
    }
}
