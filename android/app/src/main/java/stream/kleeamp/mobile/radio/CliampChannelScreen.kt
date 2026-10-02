package stream.kleeamp.mobile.radio

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.art.SeedPlate
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.ContextMenuSheet
import stream.kleeamp.mobile.chrome.EmptyNote
import stream.kleeamp.mobile.chrome.FilterRow
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.MainLayout
import stream.kleeamp.mobile.chrome.MenuKind
import stream.kleeamp.mobile.chrome.MenuSubject
import stream.kleeamp.mobile.chrome.OverflowButton
import stream.kleeamp.mobile.chrome.RetryNote
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.chrome.StationMenuArt
import stream.kleeamp.mobile.chrome.menuActions
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.chrome.rememberArt
import stream.kleeamp.mobile.chrome.scrollToTop
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.prefs.PlaylistSort
import stream.kleeamp.mobile.prefs.sortedStations
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * One cliamp radio channel as its track list, like a playlist page: filter,
 * sort, and rows that play into the queue. Channels without songs never land
 * here - their rows play the live stream straight from the stations list.
 */
@Composable
fun CliampChannelScreen(
    vm: CliampChannelViewModel,
    current: Station?,
    playing: Boolean,
    onBack: () -> Unit,
    onPlay: (Station, List<Station>) -> Unit,
    onAddToUpNext: (Station) -> Unit = {},
    onPlayNext: (Station) -> Unit = {},
    onAddToPlaylist: (Station) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val ui by vm.state.collectAsState()
    val channel = ui.channel

    var sort by rememberSaveable { mutableStateOf(PlaylistSort.Title) }
    var query by rememberSaveable { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<Station?>(null) }

    val ordered = remember(ui.tracks, sort) {
        when (sort) {
            PlaylistSort.RecentlyAdded -> sortedStations(ui.tracks, PlaylistSort.Title)
            else -> sortedStations(ui.tracks, sort)
        }
    }
    val shown = remember(ordered, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) ordered
        else ordered.filter {
            it.name.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
        }
    }

    val listState = rememberLazyListState()
    MainLayout(
        title = channel?.name ?: "cliamp radio",
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        onTitleClick = { scope.scrollToTop(listState) },
        onBack = onBack,
    ) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            item {
                ChannelHeader(
                    channel = channel,
                    onPlayAll = { first, all -> onPlay(first, all) },
                    tracks = ordered,
                )
            }

            ui.error?.let { message ->
                item {
                    RetryNote(
                        message = message,
                        prominent = true,
                        onRetry = { vm.onEvent(CliampChannelViewModel.Event.Refresh) },
                    )
                }
            }

            if (ui.loading && ordered.isEmpty()) {
                item { EmptyNote("reading the track list…") }
            } else if (ordered.isEmpty() && ui.error == null) {
                item { EmptyNote("no tracks in this channel") }
            }

            if (ordered.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        listOf(PlaylistSort.Title, PlaylistSort.Artist, PlaylistSort.Album).forEach { t ->
                            Chip(t.label, sort == t, onClick = { sort = t })
                        }
                    }
                }
                item { FilterRow(value = query, onValue = { query = it }) }
                item {
                    SectionLabel("tracks — ${shown.size}") {
                        Mono(
                            "refresh",
                            KleeampType.meta,
                            p.inkTertiary,
                            Modifier.microPress { vm.onEvent(CliampChannelViewModel.Event.Refresh) },
                        )
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            Mono("nothing matches", KleeampType.rowSecondary, p.inkFaint)
                        }
                    }
                } else {
                    items(shown, key = { it.url }) { s ->
                        ChannelTrackRow(
                            station = s,
                            active = current?.url == s.url,
                            playing = playing && current?.url == s.url,
                            onPlay = { onPlay(s, shown) },
                            onOpenMenu = { menuFor = s },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }

        menuFor?.let { s ->
            ContextMenuSheet(
                title = s.name,
                subtitle = s.artistAlbum.ifBlank { channel?.name.orEmpty() },
                art = { StationMenuArt(s) },
                actions = menuActions(
                    MenuSubject(
                        kind = MenuKind.STATION,
                        favorite = s.url in ui.favorites,
                        onPlayNext = { onPlayNext(s) },
                        onQueue = { onAddToUpNext(s) },
                        onToggleFavorite = {
                            vm.onEvent(CliampChannelViewModel.Event.ToggleFavorite(s))
                        },
                        onAddToPlaylist = { onAddToPlaylist(s) },
                    ),
                ),
                onDismiss = { menuFor = null },
            )
        }
    }
}

@Composable
private fun ChannelHeader(
    channel: CliampChannels.Channel?,
    tracks: List<Station>,
    onPlayAll: (Station, List<Station>) -> Unit,
) {
    val p = LocalPalette.current
    if (channel == null) return
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Channels carry no artwork of their own, so the header wears
            // the same generated plate a coverless track does.
            SeedPlate(
                key = channel.id,
                name = channel.name,
                modifier = Modifier
                    .size(140.dp)
                    .border(1.dp, p.frameBorder, RoundedCornerShape(KleeampShape.small)),
                radius = KleeampShape.small,
            )
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Mono(channel.name, KleeampType.rowPrimaryMedium, p.ink, maxLines = 2)
                val meta = buildList {
                    if (channel.trackCount > 0) add("${channel.trackCount} tracks")
                    if (channel.genre.isNotBlank()) add(channel.genre.lowercase())
                }.joinToString(" · ")
                if (meta.isNotBlank()) {
                    Mono(meta, KleeampType.rowSecondary, p.inkSecondary, maxLines = 1)
                }
                Spacer(Modifier.height(3.dp))
                if (tracks.isNotEmpty()) {
                    Chip("play all", selected = true, onClick = {
                        onPlayAll(tracks.first(), tracks)
                    })
                }
            }
        }
        if (channel.description.isNotBlank()) {
            Box(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, bottom = 14.dp)) {
                Mono(channel.description, KleeampType.rowSecondary, p.inkTertiary, maxLines = 4)
            }
        }
    }
}

@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
private fun ChannelTrackRow(
    station: Station,
    active: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onOpenMenu: () -> Unit = {},
) {
    val p = LocalPalette.current
    ListRow(
        rail = active,
        onClick = onPlay,
        verticalPadding = 11.dp,
        leading = {
            // Tracks carry no artwork today; the row still resolves through
            // the same art path every other row uses, so a cover on a track
            // paints instead of the generated plate with no code change.
            val artUrl = station.cover.takeIf { it.startsWith("http") }
            val thumb = rememberArt(url = artUrl)
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(KleeampShape.small))
                    .then(
                        if (active) Modifier.border(1.dp, p.accent, RoundedCornerShape(KleeampShape.small))
                        else Modifier.border(1.dp, p.frameBorder, RoundedCornerShape(KleeampShape.small))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    SeedPlate(
                        key = station.id.ifBlank { station.url },
                        name = station.name,
                        modifier = Modifier.fillMaxSize(),
                        radius = KleeampShape.small,
                    )
                }
                if (active) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(17.dp)
                            .clip(CircleShape)
                            .background(p.accent),
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
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OverflowButton(onOpenMenu)
            }
        },
    ) {
        Mono(
            station.name,
            KleeampType.rowPrimary,
            if (active) p.accent else p.ink,
            maxLines = 2,
        )
        Mono(
            station.artistAlbum.ifBlank { "cliamp radio" },
            KleeampType.rowSecondary,
            p.inkTertiary,
            maxLines = 1,
        )
    }
}
