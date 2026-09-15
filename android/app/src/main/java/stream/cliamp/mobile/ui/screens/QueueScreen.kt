package stream.cliamp.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.data.durationLabel
import stream.cliamp.mobile.playback.PlayerConnection
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.BackChevron
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.ScreenHeader
import stream.cliamp.mobile.ui.components.microPress
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.theme.CliampPalette
import stream.cliamp.mobile.ui.theme.CliampShape
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

/**
 * The always-available queue panel: a pinned "now playing" card on top, then
 * the rest as up-next with reorder / remove. Works like a music player's queue
 * — a live radio stream is one LIVE item, a local song shows its duration.
 */
@UnstableApi
@Composable
fun QueueScreen(
    player: PlayerConnection,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val queue by player.queue.collectAsState(initial = emptyList())

    // Find where the currently-playing item sits so it can be pinned on top and
    // excluded from the up-next run without disturbing the underlying order.
    val activeIndex = queue.indexOfFirst { it.url == current?.url }

    Column(Modifier.fillMaxSize().background(p.ground).navigationBarsPadding()) {
        ScreenHeader {
            Row(
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackChevron(onBack)
                Mono("Queue", CliampType.screenTitle, p.ink, maxLines = 1)
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (queue.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 28.dp)) {
                        Mono(
                            "nothing queued — play a station and its list follows it here",
                            CliampType.rowSecondary, p.inkFaint,
                        )
                    }
                }
            }

            if (current != null && activeIndex >= 0) {
                item { SectionLabel("now playing") }
                item { NowPlayingCard(current, playing, p) }
                item { Spacer(Modifier.height(22.dp)) }
            }

            val upNext = queue.filterIndexed { i, s -> activeIndex < 0 || i != activeIndex }
            if (upNext.isNotEmpty()) {
                item { SectionLabel("up next — ${upNext.size}") }
                itemsIndexed(upNext.take(200), key = { _, s -> s.url }) { _, s ->
                    val idx = queue.indexOfFirst { it.url == s.url }
                    QueueRow(
                        s = s,
                        isNow = current?.url == s.url,
                        idx = idx,
                        queueSize = queue.size,
                        onPlay = { onPlay(s, queue) },
                        onMoveUp = { player.reorderQueue(from = idx, to = idx - 1) },
                        onMoveDown = { player.reorderQueue(from = idx, to = idx + 1) },
                        onRemove = { player.removeFromQueue(idx) },
                    )
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(s: Station, playing: Boolean, p: CliampPalette) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gutter)
            .clip(RoundedCornerShape(CliampShape.medium))
            .background(p.panelRaised)
            .border(1.dp, p.keyBorder, RoundedCornerShape(CliampShape.medium))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(7.dp).clip(RoundedCornerShape(CliampShape.tiny)).background(if (playing) p.accent else p.inkFaint),
            )
            Mono(if (playing) "playing" else "paused", CliampType.chip, if (playing) p.accent else p.inkTertiary)
            Spacer(Modifier.weight(1f))
            SourceBadge(s, p)
        }
        Spacer(Modifier.height(9.dp))
        Mono(if (s.name.isBlank()) "unknown" else s.name, CliampType.trackTitleCompact, p.ink, maxLines = 1)
        Mono(sourceSubtitle(s), CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
    }
}

@Composable
private fun QueueRow(
    s: Station,
    isNow: Boolean,
    idx: Int,
    queueSize: Int,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val p = LocalPalette.current
    val title = if (s.name.isBlank()) "unknown" else s.name
    val subtitle = sourceSubtitle(s)
    ListRow(
        rail = isNow,
        onClick = onPlay,
        verticalPadding = 11.dp,
        leading = {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(CliampShape.tiny))
                    .then(
                        if (isNow) Modifier.background(p.accent)
                        else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.tiny))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isNow) {
                    Icon(CliampIcons.Pause, null, Modifier.size(9.dp), tint = p.onAccent)
                } else {
                    Icon(CliampIcons.PlayRow, null, Modifier.size(11.dp), tint = p.inkTertiary)
                }
            }
        },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SourceBadge(s, p)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SquareGlyph("^") { if (idx > 0) onMoveUp() }
                    SquareGlyph("v") { if (idx < queueSize - 1) onMoveDown() }
                    SquareGlyph("×") { onRemove() }
                }
            }
        },
    ) {
        Mono(
            title,
            if (isNow) CliampType.rowPrimaryMedium else CliampType.rowPrimary,
            if (isNow) p.accent else p.ink,
            maxLines = 1,
        )
        Mono(subtitle, CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
    }
}

/** A tight source/type label: LOCAL, LIVE, RADIO, etc. */
@Composable
private fun SourceBadge(s: Station, p: CliampPalette) {
    val label = when {
        !s.isTrack -> {
            when (s.source) {
                StationSource.Cliamp -> "cliamp"
                StationSource.Directory -> "radio"
                else -> "live"
            }
        }
        s.source == StationSource.Local -> "local"
        s.source == StationSource.Podcast -> "pod"
        else -> "track"
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(CliampShape.tiny))
            .border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.tiny))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Mono(label.uppercase(), CliampType.tabLabel, p.inkTertiary)
    }
}

private fun sourceSubtitle(s: Station): String {
    val parts = mutableListOf<String>()
    // An episode's show belongs on this line as much as a song's artist
    if ((s.source == StationSource.Local || s.source == StationSource.Podcast) &&
        s.artist.isNotBlank()
    ) parts.add(s.artist)
    else if (s.meta.isNotBlank()) parts.add(s.meta)
    if (s.isTrack && s.durationMs > 0) parts.add(durationLabel(s.durationMs))
    if (parts.isEmpty()) parts.add(if (s.isTrack) "–:––" else "live stream")
    return parts.joinToString(" · ")
}

@Composable
private fun SquareGlyph(
    label: String,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(CliampShape.tiny))
            .border(1.dp, p.keyBorder, RoundedCornerShape(CliampShape.tiny))
            .microPress(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Mono(label, CliampType.tabLabel, p.ink)
    }
}