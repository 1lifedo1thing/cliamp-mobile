package stream.cliamp.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.durationLabel
import stream.cliamp.mobile.playback.PlayerConnection
import stream.cliamp.mobile.ui.components.BrickMeter
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.rememberMeter
import stream.cliamp.mobile.ui.components.BackChevron
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.ScreenHeader
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.components.microPress
import stream.cliamp.mobile.ui.theme.CliampPalette
import stream.cliamp.mobile.ui.theme.CliampShape
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalHapticsEnabled
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
    val queue by player.queue.collectAsStateWithLifecycle()
    val queueIndex by player.queueIndex.collectAsStateWithLifecycle()
    QueueContent(
        queue = queue,
        activeIndex = queueIndex.takeIf { queue.getOrNull(it)?.url == current?.url } ?: -1,
        current = current,
        playing = playing,
        onPlay = { onPlay(it, queue) },
        onMove = { from, to ->
            if (player.queue.value == queue) player.reorderQueue(from, to)
        },
        onRemove = { index ->
            if (player.queue.value == queue) player.removeFromQueue(index)
        },
        onBack = onBack,
    )
}

@Composable
internal fun QueueContent(
    queue: List<Station>,
    activeIndex: Int,
    current: Station?,
    playing: Boolean,
    onPlay: (Station) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drag = remember(queue, activeIndex, listState) {
        QueueDragState(queueEntries(queue, activeIndex), listState, scope)
    }
    val latestOnMove by rememberUpdatedState(onMove)
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled by rememberUpdatedState(LocalHapticsEnabled.current)
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    LaunchedEffect(drag, drag.draggingKey, edge) {
        if (drag.draggingKey == null) return@LaunchedEffect
        var lastFrame = withFrameNanos { it }
        while (drag.draggingKey != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            lastFrame = now
            val speed = drag.scrollSpeed(edge)
            if (speed != 0f) listState.scrollBy(speed * seconds)
            drag.drag(0f)
        }
    }

    Column(Modifier.fillMaxSize().background(p.ground).navigationBarsPadding()) {
        ScreenHeader {
            Row(
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackChevron(onBack, Modifier.offset(y = 2.dp))
                Mono("Up next", CliampType.screenTitle, p.ink, maxLines = 1)
            }
        }

        if (current != null && activeIndex >= 0) {
            NowPlayingCard(current, playing, p)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
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

            if (drag.entries.isNotEmpty()) {
                item(key = "up-next-header") {
                    SectionLabel("up next — ${drag.entries.size}", trailing = {
                        Mono("hold to reorder", CliampType.meta, p.inkFaint)
                    })
                }
                itemsIndexed(drag.entries, key = { _, entry -> entry.key }) { index, entry ->
                    val dragging = drag.draggingKey == entry.key
                    val settling = drag.settlingKey == entry.key
                    val motion = when {
                        dragging -> Modifier.zIndex(1f).graphicsLayer { translationY = drag.dragOffset }
                        settling -> Modifier.zIndex(1f).graphicsLayer { translationY = drag.settlingOffset.value }
                        else -> Modifier.animateItem()
                    }
                    QueueRow(
                        s = entry.station,
                        dragging = dragging,
                        onPlay = { onPlay(entry.station) },
                        onRemove = { onRemove(entry.queueIndex) },
                        dragModifier = Modifier.pointerInput(drag, entry.key) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { position ->
                                    if (drag.start(entry.key, position.y) && hapticsEnabled) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag.drag(amount.y)
                                },
                                onDragEnd = { drag.finish(latestOnMove) },
                                onDragCancel = drag::cancel,
                            )
                        },
                        modifier = motion,
                        accessibilityActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction("Move up") {
                                onMove(entry.queueIndex, drag.entries[index - 1].queueIndex)
                                true
                            })
                            if (index < drag.entries.lastIndex) add(CustomAccessibilityAction("Move down") {
                                onMove(entry.queueIndex, drag.entries[index + 1].queueIndex)
                                true
                            })
                            add(CustomAccessibilityAction("Remove from queue") {
                                onRemove(entry.queueIndex)
                                true
                            })
                        },
                    )
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(s: Station, playing: Boolean, p: CliampPalette) {
    Column(Modifier.fillMaxWidth().background(p.panel)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QueueArtwork(Modifier.size(50.dp), active = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BrickMeter(
                        frame = rememberMeter(columns = 16, live = playing),
                        modifier = Modifier.size(width = 62.dp, height = 18.dp),
                        brick = 2.dp,
                        gap = 2.dp,
                        columnGap = 2.dp,
                    )
                    Mono(if (playing) "PLAYING" else "PAUSED", CliampType.tabLabel,
                        if (playing) p.accent else p.inkTertiary)
                }
                Mono(s.name.ifBlank { "unknown" }, CliampType.trackTitleSmall, p.ink, maxLines = 1)
                Mono(
                    if (s.isTrack) "${sourceSubtitle(s)} · ${queueDuration(s)}" else sourceSubtitle(s),
                    CliampType.meta, p.inkTertiary, maxLines = 1,
                )
            }
        }
        HairlineDivider(region = true)
    }
}

@Composable
private fun QueueArtwork(modifier: Modifier = Modifier, active: Boolean = false) {
    val p = LocalPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(CliampShape.small))
            .background(p.panelRaised)
            .border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.small)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(CliampIcons.MusicNote, null, Modifier.size(16.dp), tint = if (active) p.accent else p.inkFaint)
    }
}

@Composable
private fun QueueRow(
    s: Station,
    dragging: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    accessibilityActions: List<CustomAccessibilityAction>,
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    QueueSwipeToRemove(onRemove = onRemove, modifier = modifier) {
        ListRow(
            modifier = Modifier
                .background(if (dragging) p.panelRaised else p.ground)
                .then(dragModifier)
                .semantics { customActions = accessibilityActions }
                .microPress(onClick = onPlay),
            rail = dragging,
            verticalPadding = 8.dp,
            leading = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(CliampIcons.DragHandle, null, Modifier.size(16.dp),
                        tint = if (dragging) p.accent else p.inkFaint)
                    QueueArtwork(Modifier.size(42.dp))
                }
            },
            trailing = {
                if (!s.isTrack) {
                    Spacer(Modifier.width(6.dp))
                    LiveBadge(p)
                }
                Spacer(Modifier.width(12.dp))
                Mono(queueDuration(s), CliampType.timeSmall, p.inkFaint, maxLines = 1)
            },
        ) {
            Mono(s.name.ifBlank { "unknown" }, CliampType.rowPrimary, p.ink, maxLines = 1)
            Mono(sourceSubtitle(s), CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
        }
    }
}

/** Only live sources need a type badge; finite tracks use their duration. */
@Composable
private fun LiveBadge(p: CliampPalette) {
    Box(
        Modifier
            .border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.tiny))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Mono("LIVE", CliampType.tabLabel, p.inkTertiary)
    }
}

private fun queueDuration(s: Station): String =
    if (s.isTrack && s.durationMs > 0) durationLabel(s.durationMs) else "–:––"

private fun sourceSubtitle(s: Station): String = when {
    !s.isTrack -> "live stream"
    s.artist.isNotBlank() -> s.artist
    else -> "<unknown>"
}
