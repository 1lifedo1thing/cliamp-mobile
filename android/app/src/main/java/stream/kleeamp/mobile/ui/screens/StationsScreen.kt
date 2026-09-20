package stream.kleeamp.mobile.ui.screens

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.data.DirectoryQuery
import stream.kleeamp.mobile.data.PlaceholderArt
import stream.kleeamp.mobile.data.Station
import stream.kleeamp.mobile.data.StationArtSource
import stream.kleeamp.mobile.data.StationSource
import stream.kleeamp.mobile.ui.compact
import stream.kleeamp.mobile.ui.components.Chip
import stream.kleeamp.mobile.ui.components.ChipDropdown
import stream.kleeamp.mobile.ui.components.ChipOption
import stream.kleeamp.mobile.ui.components.KleeampIcons
import stream.kleeamp.mobile.ui.components.KleeampTextField
import stream.kleeamp.mobile.ui.components.EmptyNote
import stream.kleeamp.mobile.ui.components.GlyphPlate
import stream.kleeamp.mobile.ui.components.GridListToggle
import stream.kleeamp.mobile.ui.components.Gutter
import stream.kleeamp.mobile.ui.components.ListRow
import stream.kleeamp.mobile.ui.components.OverflowButton
import stream.kleeamp.mobile.ui.components.OverflowItem
import stream.kleeamp.mobile.ui.components.OverflowMenu
import stream.kleeamp.mobile.ui.components.microPress
import stream.kleeamp.mobile.ui.components.RetryNote

import stream.kleeamp.mobile.ui.components.MainLayout
import stream.kleeamp.mobile.ui.components.SectionLabel
import stream.kleeamp.mobile.ui.components.scrollToTop
import stream.kleeamp.mobile.ui.theme.KleeampShape
import stream.kleeamp.mobile.ui.theme.KleeampType
import stream.kleeamp.mobile.ui.theme.LocalPalette
import stream.kleeamp.mobile.ui.theme.Mono

private enum class Source(val label: String) {
    All("all"), Cliamp("cliamp"), Directory("directory"), Custom("custom")
}

@Composable
fun StationsScreen(
    vm: StationsViewModel,
    current: Station?,
    playing: Boolean,
    favorites: List<Station>,
    onPlay: (Station, List<Station>) -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    focusDirectory: Boolean = false,
    onDirectoryFocusConsumed: () -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var source by rememberSaveable { mutableStateOf(Source.All) }
    var addingCustom by rememberSaveable { mutableStateOf(false) }
    val ui by vm.state.collectAsState()
    val cliampGrid = ui.cliampGrid
    val directoryGrid = ui.directoryGrid
    val customGrid = ui.customGrid

    val cliamp = ui.cliamp
    val cliampError = ui.cliampError
    val custom = ui.custom
    val directory = ui.directory
    val dirStats = ui.directoryStats
    val tags = ui.tags
    val countries = ui.countries

    val listState = rememberLazyGridState()
    // NOTE: no scroll reset on query/source change. A filter keeps its
    // scroll position while the repository swaps content underneath
    // (stale rows stay until the live page lands), and returning from
    // search or the player keeps the position you left.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, directory.stations.size) {
        if (nearEnd && source != Source.Cliamp && source != Source.Custom) vm.onEvent(StationsViewModel.Event.NextPage)
    }

    // A global search in the command/tab writes its temporary query into the
    // shared directory feed. The Stations tab is "every radio" - it must not
    // inherit that, or a leftover search would silently filter the whole list.
    // Whenever this tab is active and the feed is stuck on a search, nudge it
    // back to the default full browse.
    LaunchedEffect(directory.query) {
        if (directory.query is DirectoryQuery.Search) {
            vm.onEvent(StationsViewModel.Event.LoadDirectory(DirectoryQuery.TopVoted, reset = true))
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

    MainLayout(
        title = "Stations",
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        onTitleClick = { scope.scrollToTop(listState) },
        chips = {
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
                        vm.onEvent(StationsViewModel.Event.LoadDirectory(DirectoryQuery.TopVoted, reset = true))
                    },
                ) + countries.map { c ->
                    ChipOption(c.name) {
                        vm.onEvent(StationsViewModel.Event.LoadDirectory(DirectoryQuery.Country(c.iso_3166_1, c.name), reset = true))
                    }
                },
            )
        },
    ) {

        // android LazyGrid keeps measured item spans in a per-item cache, so
        // flipping a span in place (grid/list toggle) while the directory is
        // still appending items hits a known androidx crash ("Place was called
        // on a node which was placed already"). Keying the whole grid on the
        // two mode flags remounts it fresh instead - spans are then constant
        // for the grid's whole lifetime, and a toggle just rebuilds it.
        key(cliampGrid, directoryGrid, customGrid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                // Page padding and column gaps exactly as they always were;
                // rows compensate inside themselves (8dp gutter lands on the
                // 22dp standard) and tiles pad their own rows, because any
                // grid-level vertical gap would float the row hairlines.
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {

                if (source == Source.All || source == Source.Cliamp) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel("cliamp radio — ${cliamp.size}", gutter = 8.dp) {
                            GridListToggle(cliampGrid) { vm.onEvent(StationsViewModel.Event.ToggleCliampGrid) }
                        }
                    }
                    if (cliampError != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            RetryNote(
                                message = "couldn't reach cliamp radio",
                                onRetry = { vm.onEvent(StationsViewModel.Event.RefreshCliamp) },
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
                                onToggleFavorite = { vm.onEvent(StationsViewModel.Event.ToggleFavorite(s)) },
                            )
                        } else {
                            StationRow(
                                station = s,
                                active = current?.url == s.url,
                                playing = playing && current?.url == s.url,
                                favorite = favorites.any { it.url == s.url },
                                onPlay = { onPlay(s, cliamp) },
                                onToggleFavorite = { vm.onEvent(StationsViewModel.Event.ToggleFavorite(s)) },
                            )
                        }
                    }
                    }
                }

                if (source == Source.All || source == Source.Custom) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel("custom — ${custom.size}", gutter = 8.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (!addingCustom) {
                                    Box(
                                        Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(KleeampShape.small))
                                            .background(if (p.dark) p.keyFace else p.ground)
                                            .border(1.dp, p.keyBorder, RoundedCornerShape(KleeampShape.small))
                                            .microPress(onClick = { addingCustom = true }),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(KleeampIcons.Plus, "add station", Modifier.size(16.dp), tint = p.accent)
                                    }
                                }
                                GridListToggle(customGrid) { vm.onEvent(StationsViewModel.Event.ToggleCustomGrid) }
                            }
                        }
                    }
                    if (addingCustom) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            CustomAddForm(
                                onAdd = { name, url ->
                                    customStation(name, url)?.let { s ->
                                        vm.onEvent(StationsViewModel.Event.AddCustom(s))
                                    }
                                    addingCustom = false
                                },
                                onCancel = { addingCustom = false },
                            )
                        }
                    }
                    items(
                        custom,
                        key = { "cu:${it.url}" },
                        span = { GridItemSpan(if (customGrid) 1 else maxLineSpan) },
                    ) { s ->
                        if (customGrid) {
                            StationTile(
                                station = s,
                                active = current?.url == s.url,
                                playing = playing && current?.url == s.url,
                                favorite = favorites.any { it.url == s.url },
                                onPlay = { onPlay(s, custom) },
                                onToggleFavorite = { vm.onEvent(StationsViewModel.Event.ToggleFavorite(s)) },
                                onRemove = { vm.onEvent(StationsViewModel.Event.RemoveCustom(s)) },
                            )
                        } else {
                            CustomStationRow(
                                station = s,
                                active = current?.url == s.url,
                                playing = playing && current?.url == s.url,
                                favorite = favorites.any { it.url == s.url },
                                onPlay = { onPlay(s, custom) },
                                onToggleFavorite = { vm.onEvent(StationsViewModel.Event.ToggleFavorite(s)) },
                                onRemove = { vm.onEvent(StationsViewModel.Event.RemoveCustom(s)) },
                            )
                        }
                    }
                }

                if (source == Source.All || source == Source.Directory) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel(
                            "directory — " + (dirStats?.playable?.let { "%,d".format(it) }
                                ?: if (directory.error != null) "unreachable" else "loading"),
                            gutter = 8.dp,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Mono(directory.query.label, KleeampType.meta, p.inkTertiary)
                                GridListToggle(directoryGrid) {
                                    vm.onEvent(StationsViewModel.Event.ToggleDirectoryGrid)
                                }
                            }
                        }
                    }
                    // The directory's filters, one row: the order controls (top /
                    // trending) lead it, then the tags that narrow the list.
                    // Gutter-compensated like rows and labels: grid padding
                    // plus this lands exactly on the shared gutter.
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Chip(
                                "top",
                                directory.query == DirectoryQuery.TopVoted,
                                onClick = { vm.onEvent(StationsViewModel.Event.LoadDirectory(DirectoryQuery.TopVoted, reset = true)) },
                            )
                            Chip(
                                "trending",
                                directory.query == DirectoryQuery.Trending,
                                onClick = { vm.onEvent(StationsViewModel.Event.LoadDirectory(DirectoryQuery.Trending, reset = true)) },
                            )
                            if (tags.isNotEmpty()) {
                                tags.take(24).forEach { t ->
                                    val q = directory.query
                                    Chip(
                                        t.name,
                                        selected = q is DirectoryQuery.Tag && q.tag == t.name,
                                        onClick = { vm.onEvent(StationsViewModel.Event.LoadDirectory(DirectoryQuery.Tag(t.name), reset = true)) },
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
                                onToggleFavorite = { vm.onEvent(StationsViewModel.Event.ToggleFavorite(s)) },
                            )
                        } else {
                            StationRow(
                                station = s,
                                active = current?.url == s.url,
                                playing = playing && current?.url == s.url,
                                favorite = favorites.any { it.url == s.url },
                                onPlay = { onPlay(s, directory.stations) },
                                onToggleFavorite = { vm.onEvent(StationsViewModel.Event.ToggleFavorite(s)) },
                            )
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        when {
                            directory.error != null -> RetryNote(
                                message = "couldn't fetch the directory",
                                prominent = directory.stations.isEmpty(),
                                onRetry = { vm.onEvent(StationsViewModel.Event.LoadDirectory(directory.query, reset = true)) },
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
) {
    val p = LocalPalette.current
    ListRow(
        rail = active,
        onClick = onPlay,
        verticalPadding = 9.dp,
        gutter = 8.dp,
        railOffset = 14.dp,
        leading = { StationThumb(station, active, playing) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (station.votes > 0) {
                    Mono(compact(station.votes), KleeampType.meta, p.inkFaint)
                }
                Icon(
                    if (favorite) KleeampIcons.StarFilled else KleeampIcons.Star,
                    "favourite",
                    Modifier.size(15.dp).microPress(onClick = onToggleFavorite),
                    tint = if (favorite) p.accent else p.inkFaint,
                )
            }
        },
    ) {
        Mono(
            station.name,
            KleeampType.rowPrimary,
            if (active) p.accent else p.ink,
            maxLines = 1,
        )
        Mono(
            buildList {
                if (station.source == StationSource.Cliamp) add("cliamp radio")
                station.meta.takeIf { it.isNotBlank() }?.let { add(it) }
                station.tagList.take(2).forEach { add(it) }
            }.joinToString(" · "),
            KleeampType.rowSecondary,
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
    val context = LocalContext.current
    var art by remember(station.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(station.id) {
        val fallback = PlaceholderArt.bitmapFor(context, station.id.ifBlank { station.url })
        art = if (station.source == StationSource.Cliamp) {
            fallback?.asImageBitmap()
        } else {
            (StationArtSource.bitmapForSmall(station) ?: fallback)?.asImageBitmap()
        }
    }
    val bmp = art
    // No art to show, so the plate carries the broadcast mark in accent on
    // panel - the same static themed plate playlist rows wear, recognisably
    // a radio station in every theme. The play state keeps its badge below.
    if (bmp == null && !active) {
        GlyphPlate(KleeampIcons.StationsTab, station.name, Modifier.size(40.dp))
    } else {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(KleeampShape.small))
                .then(
                    if (art != null) Modifier.background(p.panel)
                    else Modifier.border(1.dp, p.accent, RoundedCornerShape(KleeampShape.small))
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
                        .clip(RoundedCornerShape(KleeampShape.tiny))
                        .background(p.accent.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) KleeampIcons.Pause else KleeampIcons.PlayRow,
                        null,
                        Modifier.size(9.dp),
                        tint = p.onAccent,
                    )
                }
            }
        }
    }
}

/** A station as a small square tile: cover (or the broadcast mark), favourite
 * star, play badge, and name/source on a scrim. The grid layout's cell.
 * [onRemove] adds the providers-style ⋮ remove menu (custom stations only). */
@Composable
private fun StationTile(
    station: Station,
    active: Boolean,
    playing: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var art by remember(station.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(station.id) {
        val fallback = PlaceholderArt.bitmapFor(context, station.id.ifBlank { station.url })
        art = if (station.source == StationSource.Cliamp) {
            fallback?.asImageBitmap()
        } else {
            (StationArtSource.bitmapFor(station) ?: fallback)?.asImageBitmap()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            // Vertical rhythm only: columns and page padding come from the
            // grid itself, exactly as before.
            .padding(vertical = 5.dp)
            .microPress(onClick = onPlay)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(KleeampShape.medium))
                .background(p.panel)
                .then(
                    if (active) Modifier.border(2.dp, p.accent, RoundedCornerShape(KleeampShape.medium))
                    else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.medium))
                ),
        ) {
            if (art != null) {
                Image(art!!, station.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(
                    Modifier.fillMaxSize().background(p.panel),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(KleeampIcons.StationsTab, station.name, Modifier.size(26.dp), tint = p.accent)
                }
            }
            Icon(
                if (favorite) KleeampIcons.StarFilled else KleeampIcons.Star,
                "favourite",
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(15.dp).microPress(onClick = onToggleFavorite),
                tint = if (favorite) p.accent else p.inkFaint,
            )
            if (onRemove != null) {
                Box(Modifier.align(Alignment.TopStart).padding(10.dp)) {
                    OverflowMenu(
                        trigger = { open -> OverflowButton(open, size = 16) },
                        items = listOf(
                            OverflowItem(
                                "remove station",
                                color = p.destructiveInk,
                                action = onRemove,
                            ),
                        ),
                    )
                }
            }
            if (active) {
Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(24.dp)
                            .clip(RoundedCornerShape(KleeampShape.small))
                            .background(p.accent.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) KleeampIcons.Pause else KleeampIcons.PlayRow,
                        null,
                        Modifier.size(11.dp),
                        tint = p.onAccent,
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Column(Modifier.padding(horizontal = 2.dp)) {
            Mono(station.name, KleeampType.rowPrimaryMedium, p.ink, maxLines = 2)
            Mono(
                buildList {
                    if (station.source == StationSource.Cliamp) add("cliamp")
                    station.meta.takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString(" · "),
                KleeampType.rowSecondary, p.inkTertiary, maxLines = 1,
            )
        }
    }
}

/** A hand-added station: name plus stream URL, playable like anything else. */
fun customStation(name: String, rawUrl: String): Station? {
    val url = rawUrl.trim()
    if (url.isBlank()) return null
    val fixed = if ("://" in url) url else "https://$url"
    if (!fixed.startsWith("http://") && !fixed.startsWith("https://")) return null
    val label = name.trim().ifBlank {
        runCatching { java.net.URI(fixed).host }.getOrNull()?.removePrefix("www.") ?: fixed
    }
    return Station(id = "custom:$fixed", name = label, url = fixed, source = StationSource.Custom)
}

/** Name + URL form for a hand-added station, opened by the header plus key.
 * Fields wear the provider wizard's FieldRow styling exactly (label row with
 * optional marker, trackTitleSmall entry, hairline underline that accents on
 * focus); the outer 8dp keeps them on the rows' gutter inside the grid page.
 * One form serves both the All and the Custom views, so both match. */
@Composable
private fun CustomAddForm(onAdd: (String, String) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        fun save() {
            if (customStation(name, url) == null) return
            onAdd(name, url)
            name = ""
            url = ""
        }
        CustomField(
            label = "name",
            optional = true,
            value = name,
            onValue = { name = it },
            placeholder = "name",
            keyboard = KeyboardType.Text,
            imeAction = ImeAction.Next,
            autoFocus = false,
            onAction = { focus.moveFocus(FocusDirection.Next) },
        )
        CustomField(
            label = "stream url",
            optional = false,
            value = url,
            onValue = { url = it },
            placeholder = "stream url",
            keyboard = KeyboardType.Uri,
            imeAction = ImeAction.Go,
            autoFocus = false,
            onAction = ::save,
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip("cancel", selected = false, onClick = onCancel)
            Chip("save", url.isNotBlank(), onClick = ::save)
        }
    }
}

/** One provider-wizard-style field row: label (+ optional marker), entry,
 * and the hairline underline that accents while focused. */
@Composable
private fun CustomField(
    label: String,
    optional: Boolean,
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardType,
    imeAction: ImeAction,
    autoFocus: Boolean,
    onAction: () -> Unit,
) {
    val p = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Mono(label, KleeampType.rowSecondary, if (focused) p.accent else p.inkTertiary)
            if (optional) Mono("optional", KleeampType.meta, p.inkFaint)
        }
        KleeampTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            placeholder = placeholder,
            textStyle = KleeampType.trackTitleSmall,
            keyboardType = keyboard,
            imeAction = imeAction,
            autoFocus = autoFocus,
            onAction = onAction,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused) 2.dp else 1.dp)
                .background(if (focused) p.accent else p.hairline)
        )
    }
}

/** A custom station row: plays and favourites like a directory row, plus remove. */
@Composable
private fun CustomStationRow(
    station: Station,
    active: Boolean,
    playing: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRemove: () -> Unit,
) {
    val p = LocalPalette.current
    ListRow(
        rail = active,
        onClick = onPlay,
        verticalPadding = 9.dp,
        gutter = 8.dp,
        railOffset = 14.dp,
        leading = { StationThumb(station, active, playing) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (favorite) KleeampIcons.StarFilled else KleeampIcons.Star,
                    "favourite",
                    Modifier.size(15.dp).microPress(onClick = onToggleFavorite),
                    tint = if (favorite) p.accent else p.inkFaint,
                )
                OverflowMenu(
                    trigger = { open -> OverflowButton(open, size = 16) },
                    items = listOf(
                        OverflowItem(
                            "remove station",
                            color = p.destructiveInk,
                            action = onRemove,
                        ),
                    ),
                )
            }
        },
    ) {
        Mono(station.name, KleeampType.rowPrimary, if (active) p.accent else p.ink, maxLines = 1)
        Mono("custom station", KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
    }
}
