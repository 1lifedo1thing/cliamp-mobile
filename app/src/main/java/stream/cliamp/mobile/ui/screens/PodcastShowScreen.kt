package stream.cliamp.mobile.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.EpisodeProgress
import stream.cliamp.mobile.data.PodcastEpisode
import stream.cliamp.mobile.data.PodcastRepository
import stream.cliamp.mobile.data.PodcastShow
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationArtSource
import stream.cliamp.mobile.data.toStation
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.EmptyNote
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.OverflowButton
import stream.cliamp.mobile.ui.components.OverflowItem
import stream.cliamp.mobile.ui.components.OverflowMenu
import stream.cliamp.mobile.ui.components.ScreenHeader
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One show's episodes.
 *
 * Playing an episode hands the whole list to the player as the queue, so it
 * rolls on to the next one - the same thing an album does from the provider
 * browser. That works because an episode reports [Station.isTrack], which a
 * live stream does not: radio queues stay one item long by design.
 */
@Composable
fun PodcastShowScreen(
    podcasts: PodcastRepository,
    current: Station?,
    playing: Boolean,
    onBack: () -> Unit,
    onPlay: (Station, List<Station>) -> Unit,
    onAddToQueue: (Station) -> Unit = {},
    onPlayNext: (Station) -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val state by podcasts.show.collectAsState()
    val progress by podcasts.progress.collectAsState(initial = emptyMap())
    val subscriptions by podcasts.subscriptions.collectAsState(initial = emptyList())

    val show = state.show
    val subscribed = remember(subscriptions, show?.feedUrl) {
        show != null && subscriptions.any { it.feedUrl == show.feedUrl }
    }

    // Mapped once per feed load, not per row: a 300 episode list would
    // otherwise rebuild every Station on every recomposition.
    val queue = remember(state.episodes, show?.feedUrl) {
        show?.let { s -> state.episodes.map { it.toStation(s) } } ?: emptyList()
    }

    Column(Modifier.fillMaxSize().background(p.ground)) {
        ScreenHeader {
            Row(
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.clickable(onClick = onBack),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(CliampIcons.Prev, "back", Modifier.size(width = 15.dp, height = 12.dp), tint = p.inkSecondary)
                    Mono("back", CliampType.rowSecondary, p.inkSecondary)
                }
                Mono(
                    if (subscribed) "SUBSCRIBED" else "SUBSCRIBE",
                    CliampType.sectionLabel,
                    if (subscribed) p.accent else p.inkTertiary,
                    Modifier
                        .padding(end = 56.dp)
                        .clickable { show?.let { s -> scope.launch { podcasts.toggleSubscription(s) } } },
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item { ShowHeader(show) }

            state.error?.let { msg ->
                item {
                    Column {
                        Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 20.dp)) {
                            Mono("feed: $msg", CliampType.rowSecondary, p.destructiveInk)
                        }
                        HairlineDivider()
                    }
                }
            }

            if (state.loading) {
                item { EmptyNote("reading the feed…") }
            } else if (queue.isEmpty() && state.error == null) {
                item { EmptyNote("no episodes in this feed") }
            }

            if (queue.isNotEmpty()) {
                item {
                    SectionLabel("episodes — ${queue.size}") {
                        Mono("refresh", CliampType.meta, p.inkTertiary, Modifier.clickable { podcasts.refreshShow() })
                    }
                }
                items(queue.indices.toList(), key = { i -> "ep:${queue[i].url}" }) { i ->
                    val station = queue[i]
                    EpisodeRow(
                        episode = state.episodes[i],
                        station = station,
                        progress = progress[station.url],
                        active = current?.url == station.url,
                        playing = playing && current?.url == station.url,
                        onPlay = { onPlay(station, queue) },
                        onPlayNext = { onPlayNext(station) },
                        onAddToQueue = { onAddToQueue(station) },
                        onMarkPlayed = { scope.launch { podcasts.markCompleted(station) } },
                        onForget = { scope.launch { podcasts.clearProgress(station) } },
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ShowHeader(show: PodcastShow?) {
    val p = LocalPalette.current
    if (show == null) return
    var art by remember(show.artwork) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(show.artwork) {
        art = null
        if (show.artwork.startsWith("http")) {
            art = StationArtSource.bitmapForUrl(show.artwork)?.asImageBitmap()
        }
    }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(86.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .border(1.dp, p.frameBorder, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = art
                if (bmp != null) {
                    Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(CliampIcons.PodsTab, null, Modifier.size(26.dp), tint = p.inkFaint)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Mono(show.title, CliampType.rowPrimaryMedium, p.ink, maxLines = 2)
                if (show.author.isNotBlank()) {
                    Mono(show.author, CliampType.rowSecondary, p.inkSecondary, maxLines = 1)
                }
                if (show.meta.isNotBlank()) {
                    Mono(show.meta, CliampType.meta, p.inkTertiary, maxLines = 1)
                }
            }
        }
        if (show.description.isNotBlank()) {
            Box(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, bottom = 14.dp)) {
                Mono(show.description, CliampType.rowSecondary, p.inkTertiary, maxLines = 4)
            }
        }
        HairlineDivider()
    }
}

@Composable
private fun EpisodeRow(
    episode: PodcastEpisode,
    station: Station,
    progress: EpisodeProgress?,
    active: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onMarkPlayed: () -> Unit,
    onForget: () -> Unit,
) {
    val p = LocalPalette.current
    val done = progress?.completed == true
    ListRow(
        onClick = onPlay,
        verticalPadding = 11.dp,
        leading = {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .then(
                        if (active) Modifier.background(p.accent)
                        else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(4.dp))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        playing -> CliampIcons.Pause
                        done -> CliampIcons.Check
                        else -> CliampIcons.PlayRow
                    },
                    null,
                    Modifier.size(if (playing) 9.dp else 11.dp),
                    tint = when {
                        active -> p.onAccent
                        done -> p.inkFaint
                        else -> p.inkTertiary
                    },
                )
            }
        },
        trailing = {
            OverflowMenu(
                trigger = { open -> OverflowButton(open) },
                items = listOf(
                    OverflowItem("play next", onPlayNext),
                    OverflowItem("add to queue", onAddToQueue),
                    OverflowItem(
                        if (done) "mark unplayed" else "mark played",
                        { if (done) onForget() else onMarkPlayed() },
                    ),
                ),
            )
        },
    ) {
        Mono(
            station.name,
            if (active) CliampType.rowPrimaryMedium else CliampType.rowPrimary,
            when {
                active -> p.accent
                done -> p.inkTertiary
                else -> p.ink
            },
            maxLines = 2,
        )
        Mono(
            buildList {
                if (!episode.isFull) add(episode.type.lowercase())
                shortDate(episode.publishedAt)?.let { add(it) }
                clock(episode.durationMs)?.let { add(it) }
                progress?.takeIf { !it.completed && it.positionMs > 0 }?.let {
                    add("${(it.fraction * 100).toInt()}% in")
                }
                if (done) add("played")
            }.joinToString(" · "),
            CliampType.rowSecondary,
            if (progress != null && !done) p.amber else p.inkTertiary,
            maxLines = 1,
        )
    }
}

private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.US)
private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

/** `3 sep` this year, `3 sep 2024` before that. Null when the feed omitted it. */
private fun shortDate(epochMillis: Long): String? {
    if (epochMillis <= 0) return null
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val thisYear = Instant.now().atZone(zone).year
    val f = if (date.year == thisYear) dayMonth else dayMonthYear
    return date.format(f).lowercase()
}

/** `1h 35m` / `28m`. Null when the feed had no duration. */
private fun clock(durationMs: Long): String? {
    if (durationMs <= 0) return null
    val total = durationMs / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
