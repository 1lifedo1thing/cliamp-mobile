package stream.cliamp.mobile.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.DirectoryQuery
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.Repository
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationArtSource
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.ui.compact
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.ChipDropdown
import stream.cliamp.mobile.ui.components.ChipOption
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.EmptyNote
import stream.cliamp.mobile.ui.components.GridListToggle
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.microPress
import stream.cliamp.mobile.ui.components.RetryNote

import stream.cliamp.mobile.ui.components.ScreenHeader
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.theme.CliampShape
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

private enum class Source(val label: String) {
    All("all"), Cliamp("cliamp"), Directory("directory")
}

@Composable
fun StationsScreen(
    repository: Repository,
    prefs: Prefs,
    current: Station?,
    playing: Boolean,
    favorites: List<Station>,
    onPlay: (Station, List<Station>) -> Unit,
    onToggleFavorite: (Station) -> Unit,
    onAddToQueue: (Station) -> Unit = {},
    onPlayNext: (Station) -> Unit = {},
    focusDirectory: Boolean = false,
    onDirectoryFocusConsumed: () -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(Source.All) }
    val cliampGrid by prefs.cliampGrid.collectAsState(initial = prefs.cliampGrid.value)
    val directoryGrid by prefs.directoryGrid.collectAsState(initial = prefs.directoryGrid.value)

    val cliamp by repository.cliamp.collectAsState()
    val cliampError by repository.cliampError.collectAsState()
    val directory by repository.directory.collectAsState()
    val dirStats by repository.directoryStats.collectAsState()
    val tags by repository.tags.collectAsState()
    val countries by repository.countries.collectAsState(initial = emptyList())

    val listState = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, directory.stations.size) {
        if (nearEnd && source != Source.Cliamp) repository.nextPage()
    }

    // A global search in the command/tab writes its temporary query into the
    // shared directory feed. The Stations tab is "every radio" - it must not
    // inherit that, or a leftover search would silently filter the whole list.
    // Whenever this tab is active and the feed is stuck on a search, nudge it
    // back to the default full browse.
    LaunchedEffect(directory.query) {
        if (directory.query is DirectoryQuery.Search) {
            repository.loadDirectory(DirectoryQuery.TopVoted, reset = true)
        }
    }

    // A tag tapped in search lands here already filtered, but the list opens
    // at the top - above the favourites and cliamp sections. Slide down to the
    // directory section so the result is actually on screen, then report back
    // so the request is consumed and a later plain tab switch does not re-jump.
    LaunchedEffect(focusDirectory) {
        if (!focusDirectory) return@LaunchedEffect
        val directoryShown = source == Source.All || source == Source.Directory
        if (!directoryShown) return@LaunchedEffect
        var idx = 0
        if (source == Source.All || source == Source.Cliamp) idx += 1 + cliamp.size
        listState.animateScrollToItem(idx)
        onDirectoryFocusConsumed()
    }

    Column(Modifier.fillMaxSize().background(p.ground)) {
        ScreenHeader {
            Row(
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mono("Stations", CliampType.screenTitle, p.ink)
                // Settings and queue float in the top-right corner on every
                // tab via the QueueBar, so nothing else sits over it here.
                Spacer(Modifier.width(56.dp))
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Source.entries.forEach { s ->
                    Chip(s.label, source == s, onClick = { source = s })
                }
                Spacer(Modifier.width(4.dp))
                val countryQuery = directory.query as? DirectoryQuery.Country
                ChipDropdown(
                    label = countryQuery?.countryName ?: "all countries",
                    selected = countryQuery != null,
                    options = listOf(
                        ChipOption("all countries") {
                            repository.loadDirectory(DirectoryQuery.TopVoted, reset = true)
                        },
                    ) + countries.map { c ->
                        ChipOption(c.name) {
                            repository.loadDirectory(DirectoryQuery.Country(c.iso_3166_1, c.name), reset = true)
                        }
                    },
                )
            }
        }

        // android LazyGrid keeps measured item spans in a per-item cache, so
        // flipping a span in place (grid/list toggle) while the directory is
        // still appending items hits a known androidx crash ("Place was called
        // on a node which was placed already"). Keying the whole grid on the
        // two mode flags remounts it fresh instead - spans are then constant
        // for the grid's whole lifetime, and a toggle just rebuilds it.
        key(cliampGrid, directoryGrid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {

                if (source == Source.All || source == Source.Cliamp) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel("cliamp radio — ${cliamp.size}") {
                            GridListToggle(cliampGrid) { scope.launch { prefs.setCliampGrid(!cliampGrid) } }
                        }
                    }
                    if (cliampError != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            RetryNote(
                                message = "couldn't reach cliamp radio",
                                onRetry = { repository.refreshCliamp() },
                            )
                        }
                    } else {
                        items(
                            cliamp,
                            key = { "cl:${it.url}" },
                            span = { GridItemSpan(if (cliampGrid) 1 else maxLineSpan) },
                        ) { s ->
                        if (cliampGrid) {
                            StationTile(
                                station = s,
                                active = current?.url == s.url,
                                playing = playing && current?.url == s.url,
                                favorite = favorites.any { it.url == s.url },
                                onPlay = { onPlay(s, cliamp) },
                                onToggleFavorite = { onToggleFavorite(s) },
                            )
                        } else {
                            StationRow(
                                station = s,
                                active = current?.url == s.url,
                                playing = playing && current?.url == s.url,
                                favorite = favorites.any { it.url == s.url },
                                onPlay = { onPlay(s, cliamp) },
                                onToggleFavorite = { onToggleFavorite(s) },
                            )
                        }
                    }
                    }
                }

                if (source == Source.All || source == Source.Directory) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel(
                            "directory — " + (dirStats?.playable?.let { "%,d".format(it) } ?: "loading")
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Mono(directory.query.label, CliampType.meta, p.inkTertiary)
                                GridListToggle(directoryGrid) {
                                    scope.launch { prefs.setDirectoryGrid(!directoryGrid) }
                                }
                            }
                        }
                    }
                    // The directory's filters, one row: the order controls (top /
                    // trending) lead it, then the tags that narrow the list.
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(horizontal = Gutter, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Chip(
                                "top",
                                directory.query == DirectoryQuery.TopVoted,
                                onClick = { repository.loadDirectory(DirectoryQuery.TopVoted, reset = true) },
                            )
                            Chip(
                                "trending",
                                directory.query == DirectoryQuery.Trending,
                                onClick = { repository.loadDirectory(DirectoryQuery.Trending, reset = true) },
                            )
                            if (tags.isNotEmpty()) {
                                tags.take(24).forEach { t ->
                                    val q = directory.query
                                    Chip(
                                        t.name,
                                        selected = q is DirectoryQuery.Tag && q.tag == t.name,
                                        onClick = { repository.loadDirectory(DirectoryQuery.Tag(t.name), reset = true) },
                                    )
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(6.dp)) }
                    items(
                        directory.stations,
                        key = { "dir:${it.url}" },
                        span = { GridItemSpan(if (directoryGrid) 1 else maxLineSpan) },
                    ) { s ->
                        if (directoryGrid) {
                            StationTile(
                                station = s,
                                active = current?.url == s.url,
                                playing = playing && current?.url == s.url,
                                favorite = favorites.any { it.url == s.url },
                                onPlay = { onPlay(s, directory.stations) },
                                onToggleFavorite = { onToggleFavorite(s) },
                            )
                        } else {
                            StationRow(
                                station = s,
                                active = current?.url == s.url,
                                playing = playing && current?.url == s.url,
                                favorite = favorites.any { it.url == s.url },
                                onPlay = { onPlay(s, directory.stations) },
                                onToggleFavorite = { onToggleFavorite(s) },
                            )
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        when {
                            directory.error != null -> RetryNote(
                                message = "couldn't fetch the directory",
                                prominent = directory.stations.isEmpty(),
                                onRetry = { repository.loadDirectory(directory.query, reset = true) },
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

@Composable
private fun StationRow(
    station: Station,
    active: Boolean,
    playing: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToQueue: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onReplaceQueue: () -> Unit = onPlay,
) {
    val p = LocalPalette.current
    ListRow(
        rail = active,
        onClick = onPlay,
        verticalPadding = 11.dp,
        leading = { StationThumb(station, active, playing) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (station.votes > 0) {
                    Mono(compact(station.votes), CliampType.meta, p.inkFaint)
                }
                Icon(
                    if (favorite) CliampIcons.StarFilled else CliampIcons.Star,
                    "favourite",
                    Modifier.size(15.dp).microPress(onClick = onToggleFavorite),
                    tint = if (favorite) p.accent else p.inkFaint,
                )
            }
        },
    ) {
        Mono(
            station.name,
            if (active) CliampType.rowPrimaryMedium else CliampType.rowPrimary,
            if (active) p.accent else p.ink,
            maxLines = 1,
        )
        Mono(
            buildList {
                if (station.source == StationSource.Cliamp) add("cliamp radio")
                station.meta.takeIf { it.isNotBlank() }?.let { add(it) }
                station.tagList.take(2).forEach { add(it) }
            }.joinToString(" · "),
            CliampType.rowSecondary,
            p.inkTertiary,
            maxLines = 1,
        )
    }
}

/**
 * A station row's leading thumbnail. Loads real cover art through the same
 * source podcast rows use (scraped og:image / favicon, small decode, LRU
 * cache) so any station with a cover shows it, and overlays the play / pause
 * badge when it is the current track. Falls back to just the badge when there
 * is no art to show.
 */
@Composable
private fun StationThumb(station: Station, active: Boolean, playing: Boolean) {
    val p = LocalPalette.current
    var art by remember(station.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(station.id) {
        if (station.source == StationSource.Cliamp) return@LaunchedEffect
        art = StationArtSource.bitmapForSmall(station)?.asImageBitmap()
    }
    val bmp = art
Box(
        Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(CliampShape.tiny))
            .then(
                if (art != null) Modifier.background(p.panel)
                else Modifier.border(1.dp, if (active) p.accent else p.chipBorder, RoundedCornerShape(CliampShape.tiny))
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = station.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (active) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(18.dp)
                    .clip(RoundedCornerShape(CliampShape.tiny))
                    .background(p.accent.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) CliampIcons.Pause else CliampIcons.PlayRow,
                    null,
                    Modifier.size(9.dp),
                    tint = p.onAccent,
                )
            }
        } else if (bmp == null) {
            // No art to show, so the plate carries the broadcast-signal mark
            // the way a podcast row carries its feed glyph - still recognisably
            // a radio station, not just an empty play affordance. The real play
            // state still gets its accent badge below.
            Icon(CliampIcons.StationsTab, null, Modifier.size(15.dp), tint = p.inkTertiary)
        }
    }
}

/** A station as a small square tile: cover (or the broadcast mark), favourite
 * star, play badge, and name/source on a scrim. The grid layout's cell. */
@Composable
private fun StationTile(
    station: Station,
    active: Boolean,
    playing: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val p = LocalPalette.current
    var art by remember(station.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(station.id) {
        if (station.source == StationSource.Cliamp) return@LaunchedEffect
        art = StationArtSource.bitmapFor(station)?.asImageBitmap()
    }
    Column(Modifier.fillMaxWidth().microPress(onClick = onPlay)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(CliampShape.medium))
                .background(p.panel)
                .then(
                    if (active) Modifier.border(2.dp, p.accent, RoundedCornerShape(CliampShape.medium))
                    else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.medium))
                ),
        ) {
            if (art != null) {
                Image(art!!, station.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(
                    Modifier.fillMaxSize().background(if (p.dark) p.ground else p.keyFace),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CliampIcons.StationsTab, null, Modifier.size(26.dp), tint = p.chipBorder)
                }
            }
            Icon(
                if (favorite) CliampIcons.StarFilled else CliampIcons.Star,
                "favourite",
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(15.dp).microPress(onClick = onToggleFavorite),
                tint = if (favorite) p.accent else p.inkFaint,
            )
            if (active) {
Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(24.dp)
                            .clip(RoundedCornerShape(CliampShape.small))
                            .background(p.accent.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) CliampIcons.Pause else CliampIcons.PlayRow,
                        null,
                        Modifier.size(11.dp),
                        tint = p.onAccent,
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Column(Modifier.padding(horizontal = 2.dp)) {
            Mono(station.name, CliampType.rowPrimaryMedium, p.ink, maxLines = 2)
            Mono(
                buildList {
                    if (station.source == StationSource.Cliamp) add("cliamp")
                    station.meta.takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString(" · "),
                CliampType.rowSecondary, p.inkTertiary, maxLines = 1,
            )
        }
    }
}
