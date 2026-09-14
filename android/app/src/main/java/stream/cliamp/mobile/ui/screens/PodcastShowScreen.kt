package stream.cliamp.mobile.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import stream.cliamp.mobile.data.DownloadState
import stream.cliamp.mobile.data.downloadSizeLabel
import stream.cliamp.mobile.data.EpisodeProgress
import stream.cliamp.mobile.data.PodcastEpisode
import stream.cliamp.mobile.data.PodcastShow
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationArtSource
import stream.cliamp.mobile.data.toStation
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.EmptyNote
import stream.cliamp.mobile.ui.components.RetryNote
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.microPress
import stream.cliamp.mobile.ui.components.OverflowButton
import stream.cliamp.mobile.ui.components.OverflowItem
import stream.cliamp.mobile.ui.components.OverflowMenu
import stream.cliamp.mobile.ui.components.MainLayout
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.components.scrollToTop
import stream.cliamp.mobile.ui.theme.CliampShape
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
    vm: PodcastShowViewModel,
    current: Station?,
    playing: Boolean,
    onBack: () -> Unit,
    onPlay: (Station, List<Station>) -> Unit,
    onAddToQueue: (Station) -> Unit = {},
    onPlayNext: (Station) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val ui by vm.state.collectAsState()
    val dlStates = ui.dlStates
    val dlEntries = ui.dlEntries
    val autoOn = ui.autoDownload
    val state = ui.showState
    val progress = ui.progress
    val subscriptions = ui.subscriptions

    val show = state.show
    val subscribed = remember(subscriptions, show?.feedUrl) {
        show != null && subscriptions.any { it.feedUrl == show.feedUrl }
    }

    // Mapped once per feed load, not per row: a 300 episode list would
    // otherwise rebuild every Station on every recomposition.
    val queue = remember(state.episodes, show?.feedUrl) {
        show?.let { s -> state.episodes.map { it.toStation(s) } } ?: emptyList()
    }
    val listState = rememberLazyListState()

    // Subscribed + auto-download on: the latest episodes fetch themselves
    // whenever the feed lands. Idempotent, so refreshes re-firing it cost
    // nothing; the toggle being off keeps this dead.
    LaunchedEffect(show?.feedUrl, subscribed, state.episodes, autoOn) {
        val s = show
        if (s != null && subscribed && autoOn && state.episodes.isNotEmpty()) {
            vm.onEvent(PodcastShowViewModel.Event.AutoDownload)
        }
    }

    MainLayout(
        title = show?.title ?: "podcasts",
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        onTitleClick = { scope.scrollToTop(listState) },
        onBack = onBack,
        chips = {
            Chip(
                if (subscribed) "subscribed" else "subscribe",
                selected = subscribed,
                onClick = { show?.let { s -> vm.onEvent(PodcastShowViewModel.Event.ToggleSubscription(s)) } },
            )
        },
    ) {

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            item { ShowHeader(show) }

            state.error?.let {
                item {
                    RetryNote(
                        message = "couldn't read the feed",
                        prominent = true,
                        onRetry = { vm.onEvent(PodcastShowViewModel.Event.RefreshShow) },
                    )
                }
            }

            // The loading note only shows on an empty list: with episodes on
            // screen a refresh runs silently behind them instead of pushing
            // a row in above and shifting everything when it lands.
            if (state.loading && queue.isEmpty()) {
                item { EmptyNote("reading the feed…") }
            } else if (queue.isEmpty() && state.error == null) {
                item { EmptyNote("no episodes in this feed") }
            }

            if (queue.isNotEmpty()) {
                item {
                    SectionLabel("episodes — ${queue.size}") {
                        Mono("refresh", CliampType.meta, p.inkTertiary, Modifier.microPress { vm.onEvent(PodcastShowViewModel.Event.RefreshShow) })
                    }
                }
                items(queue.indices.toList(), key = { i -> "ep:${queue[i].url}" }) { i ->
                    val station = queue[i]
                    val dl = dlStates[station.url] ?: DownloadState.Idle
                    val fetched = dlEntries[station.url]
                    EpisodeRow(
                        episode = state.episodes[i],
                        station = station,
                        progress = progress[station.url],
                        active = current?.url == station.url,
                        playing = playing && current?.url == station.url,
                        dlState = dl,
                        downloadedBytes = fetched?.bytes ?: 0L,
                        onPlay = { onPlay(station, queue) },
                        onPlayNext = { onPlayNext(station) },
                        onAddToQueue = { onAddToQueue(station) },
                        onMarkPlayed = { vm.onEvent(PodcastShowViewModel.Event.MarkCompleted(station)) },
                        onForget = { vm.onEvent(PodcastShowViewModel.Event.ClearProgress(station)) },
                        onDownload = { vm.onEvent(PodcastShowViewModel.Event.Download(station)) },
                        onCancelDownload = { vm.onEvent(PodcastShowViewModel.Event.CancelDownload(station.url)) },
                        onRemoveDownload = { vm.onEvent(PodcastShowViewModel.Event.RemoveDownload(station.url)) },
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
                    .size(140.dp)
                    .clip(RoundedCornerShape(CliampShape.small))
                    .border(1.dp, p.frameBorder, RoundedCornerShape(CliampShape.small)),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = art
                if (bmp != null) {
                    Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(CliampIcons.PodsTab, null, Modifier.size(40.dp), tint = p.inkFaint)
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
    dlState: DownloadState = DownloadState.Idle,
    downloadedBytes: Long = 0L,
    onDownload: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onRemoveDownload: () -> Unit = {},
) {
    val p = LocalPalette.current
    val done = progress?.completed == true
    val fetched = downloadedBytes > 0L
    ListRow(
        rail = active,
        onClick = onPlay,
        verticalPadding = 11.dp,
        leading = {
            // The resolved station cover already falls back to the show's own
            // artwork when the episode has none (see toStation), so only a
            // show without any cover at all lands on the icon plate.
            val artUrl = station.cover.takeIf { it.startsWith("http") }
                ?: episode.artwork.takeIf { it.startsWith("http") }
            var thumb by remember(artUrl) {
                mutableStateOf(artUrl?.let { StationArtSource.cachedSmallUrl(it)?.asImageBitmap() })
            }
            if (artUrl != null) {
                LaunchedEffect(artUrl) {
                    if (thumb != null) return@LaunchedEffect
                    thumb = StationArtSource.bitmapForUrlSmall(artUrl)?.asImageBitmap()
                }
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(CliampShape.small))
                    .then(
                        if (active) Modifier.border(1.dp, p.accent, RoundedCornerShape(CliampShape.small))
                        else Modifier.border(1.dp, p.frameBorder, RoundedCornerShape(CliampShape.small))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val b = thumb
                if (b != null) {
                    Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(CliampIcons.PodRow, null, Modifier.size(18.dp), tint = p.inkFaint)
                }
                if (active || done) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(17.dp)
                            .clip(CircleShape)
                            .background(if (active) p.accent else p.chipBorder),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            when {
                                playing -> CliampIcons.Pause
                                done -> CliampIcons.Check
                                else -> CliampIcons.PlayRow
                            },
                            null,
                            Modifier.size(9.dp),
                            tint = if (active) p.onAccent else p.inkFaint,
                        )
                    }
                }
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                // Fetch state lives left of the ⋮: idle offers the download,
                // active reads percent (tap cancels), done wears accent (the
                // ⋮ removes it), failed offers a retry.
                when (val d = dlState) {
                    is DownloadState.Active -> Mono(
                        if (d.indeterminate) downloadSizeLabel(d.bytesRead)
                        else "${(d.fraction * 100).toInt()}%",
                        CliampType.meta, p.amber,
                        Modifier.padding(8.dp, 4.dp).microPress { onCancelDownload() },
                    )
                    is DownloadState.Failed -> Mono(
                        "retry", CliampType.meta, p.destructiveInk,
                        Modifier.padding(8.dp, 4.dp).microPress { onDownload() },
                    )
                    is DownloadState.Idle ->
                        if (fetched) Icon(
                            CliampIcons.Download, "downloaded",
                            Modifier.size(15.dp), tint = p.accent,
                        )
                        else Icon(
                            CliampIcons.Download, "download",
                            Modifier.size(15.dp).microPress { onDownload() }, tint = p.inkTertiary,
                        )
                }
                OverflowMenu(
                    trigger = { open -> OverflowButton(open) },
                    items = buildList {
                        add(OverflowItem("play next", onPlayNext))
                        add(OverflowItem("add to queue", onAddToQueue))
                        when {
                            fetched -> add(
                                OverflowItem("remove download", color = p.destructiveInk, action = onRemoveDownload)
                            )
                            dlState is DownloadState.Active -> add(
                                OverflowItem("cancel download", color = p.destructiveInk, action = onCancelDownload)
                            )
                            else -> add(OverflowItem("download", onDownload))
                        }
                        add(
                            OverflowItem(
                                if (done) "mark unplayed" else "mark played",
                                { if (done) onForget() else onMarkPlayed() },
                            )
                        )
                    },
                )
            }
        },
    ) {
        Mono(
            station.name,
            CliampType.rowPrimary,
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
                if (fetched) add("offline · ${downloadSizeLabel(downloadedBytes)}")
                else when (val d = dlState) {
                    is DownloadState.Active -> add(
                        if (d.indeterminate) "fetching ${downloadSizeLabel(d.bytesRead)}"
                        else "fetching ${(d.fraction * 100).toInt()}%"
                    )
                    is DownloadState.Failed -> add(d.reason)
                    is DownloadState.Idle -> {}
                }
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
