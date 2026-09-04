package stream.cliamp.mobile.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import stream.cliamp.mobile.data.PodcastDirectory
import stream.cliamp.mobile.data.PodcastQuery
import stream.cliamp.mobile.data.PodcastRepository
import stream.cliamp.mobile.data.PodcastShow
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationArtSource
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.EmptyNote
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.OverflowButton
import stream.cliamp.mobile.ui.components.OverflowItem
import stream.cliamp.mobile.ui.components.OverflowMenu
import stream.cliamp.mobile.ui.components.ScreenHeader
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

private enum class Pane(val label: String) {
    All("all"), Subs("subscribed"), Directory("directory")
}

/**
 * Podcasts, laid out as the Stations tab is: the lists you own on top, the
 * directory below, one flat scroll, paged as it runs out.
 *
 * Where radio has favourites this has two owned lists, because a show and an
 * episode are different things: subscriptions are shows, and "continue" is
 * episodes with a saved position. The second is the one that earns its place at
 * the top - a half-finished episode is what you almost always came back for.
 */
@Composable
fun PodcastsScreen(
    podcasts: PodcastRepository,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onOpenShow: (PodcastShow) -> Unit,
    onAddToQueue: (Station) -> Unit = {},
    onPlayNext: (Station) -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var pane by remember { mutableStateOf(Pane.All) }

    val directory by podcasts.directory.collectAsState()
    val subscriptions by podcasts.subscriptions.collectAsState(initial = emptyList())
    val continueList by podcasts.continueListening.collectAsState(initial = emptyList())
    val progress by podcasts.progress.collectAsState(initial = emptyMap())

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, directory.shows.size) {
        if (nearEnd && pane != Pane.Subs) podcasts.nextPage()
    }

    val subscribedFeeds = remember(subscriptions) { subscriptions.mapTo(HashSet()) { it.feedUrl } }

    Column(Modifier.fillMaxSize().background(p.ground)) {
        ScreenHeader {
            Row(
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mono("Podcasts", CliampType.screenTitle, p.ink)
                // clear of the settings arm and queue button
                Spacer(Modifier.width(56.dp))
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Pane.entries.forEach { s -> Chip(s.label, pane == s, onClick = { pane = s }) }
                Spacer(Modifier.width(4.dp))
                Chip(
                    "top",
                    directory.query == PodcastQuery.Top,
                    onClick = { podcasts.load(PodcastQuery.Top, reset = true) },
                )
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {

            if (pane != Pane.Directory && continueList.isNotEmpty()) {
                item { SectionLabel("continue — ${continueList.size}") }
                items(continueList, key = { "cont:${it.url}" }) { episode ->
                    EpisodeResumeRow(
                        episode = episode,
                        progress = progress[episode.url],
                        active = current?.url == episode.url,
                        playing = playing && current?.url == episode.url,
                        onPlay = { onPlay(episode, continueList) },
                        onPlayNext = { onPlayNext(episode) },
                        onAddToQueue = { onAddToQueue(episode) },
                        onForget = { scope.launch { podcasts.clearProgress(episode) } },
                    )
                }
            }

            if (pane != Pane.Directory) {
                item { SectionLabel("subscribed — ${subscriptions.size}") }
                if (subscriptions.isEmpty()) {
                    item { EmptyNote("nothing subscribed — open a show and hit the star") }
                } else {
                    items(subscriptions, key = { "sub:${it.feedUrl}" }) { show ->
                        ShowRow(
                            show = show,
                            subscribed = true,
                            onOpen = { onOpenShow(show) },
                            onToggleSubscribe = { scope.launch { podcasts.toggleSubscription(show) } },
                        )
                    }
                }
            }

            if (pane != Pane.Subs) {
                item {
                    SectionLabel("directory") {
                        Mono(directory.query.label, CliampType.meta, p.inkTertiary)
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = Gutter, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        PodcastDirectory.genres.forEach { g ->
                            val q = directory.query
                            Chip(
                                g.name.lowercase(),
                                selected = q is PodcastQuery.Category && q.genre.id == g.id,
                                onClick = { podcasts.load(PodcastQuery.Category(g), reset = true) },
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(6.dp)) }

                items(directory.shows, key = { "dir:${it.feedUrl}" }) { show ->
                    ShowRow(
                        show = show,
                        subscribed = show.feedUrl in subscribedFeeds,
                        onOpen = { onOpenShow(show) },
                        onToggleSubscribe = { scope.launch { podcasts.toggleSubscription(show) } },
                    )
                }
                item {
                    when {
                        directory.error != null -> EmptyNote("directory: ${directory.error}")
                        directory.loading -> EmptyNote("loading more…")
                        directory.exhausted -> EmptyNote("end of ${directory.query.label}")
                        else -> Spacer(Modifier.height(8.dp))
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

/** A show: artwork, title, author and count, with the subscribe star. */
@Composable
private fun ShowRow(
    show: PodcastShow,
    subscribed: Boolean,
    onOpen: () -> Unit,
    onToggleSubscribe: () -> Unit,
) {
    val p = LocalPalette.current
    ListRow(
        onClick = onOpen,
        verticalPadding = 11.dp,
        leading = { Artwork(show.artwork) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (subscribed) CliampIcons.StarFilled else CliampIcons.Star,
                    "subscribe",
                    Modifier.size(15.dp).clickable(onClick = onToggleSubscribe),
                    tint = if (subscribed) p.accent else p.inkFaint,
                )
                Icon(CliampIcons.CaretRight, null, Modifier.size(9.dp), tint = p.inkFaint)
            }
        },
    ) {
        Mono(show.title, CliampType.rowPrimary, p.ink, maxLines = 1)
        Mono(show.meta, CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
    }
}

/**
 * An episode with somewhere to get back to. The show name is the second line
 * rather than the episode's own metadata, because out of the show's own screen
 * "which podcast is this" is the question being asked.
 */
@Composable
private fun EpisodeResumeRow(
    episode: Station,
    progress: EpisodeProgress?,
    active: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onForget: () -> Unit,
) {
    val p = LocalPalette.current
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
                    if (playing) CliampIcons.Pause else CliampIcons.PlayRow,
                    null,
                    Modifier.size(if (playing) 9.dp else 11.dp),
                    tint = if (active) p.onAccent else p.inkTertiary,
                )
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                progress?.let { Mono(remaining(it), CliampType.timeSmall, p.amber) }
                OverflowMenu(
                    trigger = { open -> OverflowButton(open) },
                    items = listOf(
                        OverflowItem("play next", onPlayNext),
                        OverflowItem("add to queue", onAddToQueue),
                        OverflowItem("forget position", onForget),
                    ),
                )
            }
        },
    ) {
        Mono(
            episode.name,
            if (active) CliampType.rowPrimaryMedium else CliampType.rowPrimary,
            if (active) p.accent else p.ink,
            maxLines = 1,
        )
        Mono(episode.artist.ifBlank { "podcast" }, CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
    }
}

/**
 * Show artwork, or the mic if there is none yet. Loaded through the same source
 * radio favicons use, which already caches by URL and decodes small.
 */
@Composable
private fun Artwork(url: String) {
    val p = LocalPalette.current
    var art by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        art = null
        if (url.startsWith("http")) {
            art = StationArtSource.bitmapForUrl(url)?.asImageBitmap()
        }
    }
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, p.chipBorder, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = art
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(CliampIcons.PodRow, null, Modifier.size(13.dp), tint = p.inkFaint)
        }
    }
}

/** `24m left`, which is the number that decides whether to press play. */
private fun remaining(progress: EpisodeProgress): String {
    val left = ((progress.durationMs - progress.positionMs) / 1000).coerceAtLeast(0L)
    return when {
        progress.durationMs <= 0 -> "started"
        left >= 3600 -> "${left / 3600}h ${(left % 3600) / 60}m left"
        left >= 60 -> "${left / 60}m left"
        else -> "${left}s left"
    }
}
