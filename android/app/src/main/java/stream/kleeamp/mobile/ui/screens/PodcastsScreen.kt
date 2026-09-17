package stream.kleeamp.mobile.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.data.PodcastDirectory
import stream.kleeamp.mobile.data.PodcastQuery
import stream.kleeamp.mobile.data.PodcastShow
import stream.kleeamp.mobile.data.StationArtSource
import stream.kleeamp.mobile.ui.components.Chip
import stream.kleeamp.mobile.ui.components.ChipDropdown
import stream.kleeamp.mobile.ui.components.ChipOption
import stream.kleeamp.mobile.ui.components.KleeampIcons
import stream.kleeamp.mobile.ui.components.EmptyNote
import stream.kleeamp.mobile.ui.components.GridListToggle
import stream.kleeamp.mobile.ui.components.microPress
import stream.kleeamp.mobile.ui.components.Gutter
import stream.kleeamp.mobile.ui.components.ListRow
import stream.kleeamp.mobile.ui.components.RetryNote
import stream.kleeamp.mobile.ui.components.MainLayout
import stream.kleeamp.mobile.ui.components.SectionLabel
import stream.kleeamp.mobile.ui.components.scrollToTop
import stream.kleeamp.mobile.ui.theme.KleeampShape
import stream.kleeamp.mobile.ui.theme.KleeampType
import stream.kleeamp.mobile.ui.theme.LocalPalette
import stream.kleeamp.mobile.ui.theme.Mono

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
    vm: PodcastsViewModel,
    onOpenShow: (PodcastShow) -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var pane by rememberSaveable { mutableStateOf(Pane.All) }
    val ui by vm.state.collectAsState()
    val subsGrid = ui.subsGrid
    val podDirectoryGrid = ui.podDirectoryGrid

    val countryList = ui.countries

    val directory = ui.directory
    val subscriptions = ui.subscriptions

    val listState = rememberLazyGridState()
    // NOTE: no scroll reset on query/pane change, same reasoning as Stations.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 16
        }
    }
    LaunchedEffect(nearEnd, directory.shows.size) {
        if (nearEnd && pane != Pane.Subs) vm.onEvent(PodcastsViewModel.Event.NextPage)
    }

    val subscribedFeeds = remember(subscriptions) { subscriptions.mapTo(HashSet()) { it.feedUrl } }

    MainLayout(
        title = "Podcasts",
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        onTitleClick = { scope.scrollToTop(listState) },
        chips = {
            Pane.entries.forEach { s -> Chip(s.label, pane == s, onClick = { pane = s }) }
            Spacer(Modifier.width(4.dp))
            val topQuery = directory.query as? PodcastQuery.Top
            ChipDropdown(
                label = topQuery?.country?.takeIf { it.isNotEmpty() }?.let { c ->
                    countryList.firstOrNull { it.iso_3166_1.equals(c, ignoreCase = true) }?.name ?: c
                } ?: "all countries",
                selected = topQuery != null && topQuery.country.isNotEmpty(),
                options = listOf(
                    ChipOption("all countries") {
                        vm.onEvent(PodcastsViewModel.Event.Load(PodcastQuery.Top()))
                    },
                ) + countryList.map { c ->
                    ChipOption(c.name) {
                        vm.onEvent(PodcastsViewModel.Event.Load(PodcastQuery.Top(c.iso_3166_1)))
                    }
                },
            )
        },
    ) {

        // Same known androidx LazyGrid span-flip crash guard as Stations:
        // remount the grid when either mode toggle changes so measured item
        // spans never flip in place while the directory is appending pages.
        key(subsGrid, podDirectoryGrid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                // Same split as Stations: page padding and column gaps from
                // the grid itself, row rhythm from the rows, tile rhythm
                // from the tiles - grid mode pixel-identical to before.
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {

                if (pane == Pane.Subs) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel("subscribed — ${subscriptions.size}", gutter = 8.dp) {
                            GridListToggle(subsGrid) { vm.onEvent(PodcastsViewModel.Event.ToggleSubsGrid) }
                        }
                    }
                    if (subscriptions.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyNote("nothing subscribed — open a show and hit the star")
                        }
                    } else {
                        items(
                            subscriptions,
                            key = { "sub:${it.feedUrl}" },
                            span = { GridItemSpan(if (subsGrid) 1 else maxLineSpan) },
                        ) { show ->
                            if (subsGrid) {
                                ShowTile(
                                    show = show,
                                    subscribed = true,
                                    onOpen = { onOpenShow(show) },
                                    onToggleSubscribe = { vm.onEvent(PodcastsViewModel.Event.ToggleSubscription(show)) },
                                )
                            } else {
                                ShowRow(
                                    show = show,
                                    subscribed = true,
                                    onOpen = { onOpenShow(show) },
                                    onToggleSubscribe = { vm.onEvent(PodcastsViewModel.Event.ToggleSubscription(show)) },
                                )
                            }
                        }
                    }
                }

                if (pane != Pane.Subs) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel("directory", gutter = 8.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Mono(directory.query.label, KleeampType.meta, p.inkTertiary)
                                GridListToggle(podDirectoryGrid) {
                                    vm.onEvent(PodcastsViewModel.Event.TogglePodDirectoryGrid)
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            // The order control leads the filters: the current
                            // chart order, then the genres that narrow it.
                            val topQuery = directory.query as? PodcastQuery.Top
                            Chip(
                                "top",
                                topQuery != null,
                                onClick = { vm.onEvent(PodcastsViewModel.Event.Load(PodcastQuery.Top())) },
                            )
                            PodcastDirectory.genres.forEach { g ->
                                val q = directory.query
                                Chip(
                                    g.name.lowercase(),
                                    selected = q is PodcastQuery.Category && q.genre.id == g.id,
                                    onClick = { vm.onEvent(PodcastsViewModel.Event.Load(PodcastQuery.Category(g))) },
                                )
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(6.dp)) }

                    items(
                        directory.shows,
                        key = { "dir:${it.feedUrl}" },
                        span = { GridItemSpan(if (podDirectoryGrid) 1 else maxLineSpan) },
                    ) { show ->
                        if (podDirectoryGrid) {
                            ShowTile(
                                show = show,
                                subscribed = show.feedUrl in subscribedFeeds,
                                onOpen = { onOpenShow(show) },
                                onToggleSubscribe = { vm.onEvent(PodcastsViewModel.Event.ToggleSubscription(show)) },
                            )
                        } else {
                            ShowRow(
                                show = show,
                                subscribed = show.feedUrl in subscribedFeeds,
                                onOpen = { onOpenShow(show) },
                                onToggleSubscribe = { vm.onEvent(PodcastsViewModel.Event.ToggleSubscription(show)) },
                            )
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        when {
                            directory.error != null -> RetryNote(
                                message = "couldn't fetch the directory",
                                prominent = directory.shows.isEmpty(),
                                onRetry = { vm.onEvent(PodcastsViewModel.Event.Load(directory.query)) },
                            )
                            directory.loading -> EmptyNote("loading more…")
                            directory.exhausted -> EmptyNote("end of ${directory.query.label}")
                            else -> Spacer(Modifier.height(8.dp))
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(20.dp)) }
            }
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
        verticalPadding = 9.dp,
        gutter = 8.dp,
        leading = { Artwork(show.artwork) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (subscribed) KleeampIcons.StarFilled else KleeampIcons.Star,
                    "subscribe",
                    Modifier.size(15.dp).microPress(onClick = onToggleSubscribe),
                    tint = if (subscribed) p.accent else p.inkFaint,
                )
                Icon(KleeampIcons.CaretRight, null, Modifier.size(9.dp), tint = p.inkFaint)
            }
        },
    ) {
        Mono(show.title, KleeampType.rowPrimary, p.ink, maxLines = 1)
        Mono(show.meta, KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
    }
}

/**
 * Show artwork, or the mic if there is none yet. A row icon only ever sits at
 * thumbnails size, so it decodes small like a station's row thumbnail does;
 * the grid tile decodes full-res separately.
 */
@Composable
private fun Artwork(url: String) {
    val p = LocalPalette.current
    var art by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        art = null
        if (url.startsWith("http")) {
            art = StationArtSource.bitmapForUrlSmall(url)?.asImageBitmap()
        } else {
            art = null
        }
    }
    val bmp = art
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.small)),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(KleeampIcons.PodRow, null, Modifier.size(15.dp), tint = p.inkFaint)
        }
    }
}

/** A show as a small square tile: artwork (or the mic mark), subscribe star,
 * and title/author on a scrim. The grid layout's cell. */
@Composable
private fun ShowTile(
    show: PodcastShow,
    subscribed: Boolean,
    onOpen: () -> Unit,
    onToggleSubscribe: () -> Unit,
) {
    val p = LocalPalette.current
    var art by remember(show.artwork) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(show.artwork) {
        art = null
        if (show.artwork.startsWith("http")) {
            art = StationArtSource.bitmapForUrl(show.artwork)?.asImageBitmap()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .microPress(onClick = onOpen)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(KleeampShape.medium))
                .background(p.panel)
                .border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.medium)),
        ) {
            if (art != null) {
                Image(art!!, show.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(
                    Modifier.fillMaxSize().background(if (p.dark) p.ground else p.keyFace),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(KleeampIcons.PodRow, null, Modifier.size(26.dp), tint = p.chipBorder)
                }
            }
            Icon(
                if (subscribed) KleeampIcons.StarFilled else KleeampIcons.Star,
                "subscribe",
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(15.dp).microPress(onClick = onToggleSubscribe),
                tint = if (subscribed) p.accent else p.inkFaint,
            )
        }
        Spacer(Modifier.height(7.dp))
        Column(Modifier.padding(horizontal = 2.dp)) {
            Mono(show.title, KleeampType.rowPrimaryMedium, p.ink, maxLines = 2)
            Mono(show.meta, KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
        }
    }
}
