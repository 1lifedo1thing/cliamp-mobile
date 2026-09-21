package stream.kleeamp.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.data.PlaylistSort
import stream.kleeamp.mobile.data.Station
import stream.kleeamp.mobile.data.provider.toStation
import stream.kleeamp.mobile.ui.components.BackChevron
import stream.kleeamp.mobile.ui.components.Chip
import stream.kleeamp.mobile.ui.components.FilterRow
import stream.kleeamp.mobile.ui.components.KleeampIcons
import stream.kleeamp.mobile.ui.components.Gutter
import stream.kleeamp.mobile.ui.components.scrollToTop
import stream.kleeamp.mobile.ui.components.ListRow
import stream.kleeamp.mobile.ui.components.microPress
import stream.kleeamp.mobile.ui.components.SectionLabel
import stream.kleeamp.mobile.ui.theme.KleeampShape
import stream.kleeamp.mobile.ui.theme.KleeampType
import stream.kleeamp.mobile.ui.theme.LocalPalette
import stream.kleeamp.mobile.ui.theme.Mono

/**
 * Browses one provider's library: albums, artists, drilling into an album's
 * track list. Playing a track queues the whole album.
 *
 * The screen only knows the generic browse client behind [vm]. Sorting wears
 * exactly the local-songs chips; the chips pick both the server query and
 * the client-side order. A filter row narrows whatever list is showing
 * by name.
 */
@Composable
fun ProviderBrowseScreen(
    vm: ProviderBrowseViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onPlay: (Station, List<Station>) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val browseListState = rememberLazyListState()
    val uiState by vm.state.collectAsState()

    val here = uiState.stack.last()

    fun pop() {
        if (uiState.stack.size > 1) vm.onEvent(ProviderBrowseViewModel.Event.Pop) else onBack()
    }

    androidx.activity.compose.BackHandler(enabled = uiState.stack.size > 1) {
        vm.onEvent(ProviderBrowseViewModel.Event.Pop)
    }

    Column(Modifier.fillMaxSize().background(p.ground).navigationBarsPadding()) {
        stream.kleeamp.mobile.ui.components.ScreenHeader {
            Row(
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackChevron({ pop() })
                // clear of QueueBar
                Mono(
                    "EDIT",
                    KleeampType.sectionLabel,
                    p.inkTertiary,
                    Modifier.padding(end = 56.dp).microPress(onClick = onEdit),
                )
            }
            Box(Modifier.padding(horizontal = Gutter, vertical = 4.dp)) {
                Mono(
                    when (val n = here) {
                        Node.Home -> vm.account.label.ifBlank { "provider" }
                        is Node.Artist -> n.name
                        is Node.Album -> n.name
                    },
                    KleeampType.screenTitle, p.ink, maxLines = 1,
                    modifier = Modifier.microPress { scope.scrollToTop(browseListState) },
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                PlaylistSort.entries.forEach { s ->
                    Chip(
                        s.label,
                        uiState.sort == s,
                        onClick = { vm.onEvent(ProviderBrowseViewModel.Event.SortChanged(s)) },
                    )
                }
                if (here == Node.Home && uiState.hasIndex) {
                    Chip(
                        if (uiState.indexState.scanning) "scanning" else "rescan",
                        selected = false,
                        onClick = { vm.onEvent(ProviderBrowseViewModel.Event.Reindex) },
                    )
                }
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = browseListState) {
            item {
                FilterRow(
                    value = uiState.filter,
                    onValue = { vm.onEvent(ProviderBrowseViewModel.Event.FilterChanged(it)) },
                )
            }
            if (uiState.indexState.text.isNotBlank()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp)) {
                        Mono(
                            uiState.indexState.text,
                            KleeampType.rowSecondary,
                            if (uiState.indexState.scanning) p.amber else p.inkFaint,
                            maxLines = 1,
                        )
                    }
                }
            }
            uiState.failure?.let { msg ->
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 20.dp)) {
                        Mono(msg, KleeampType.rowSecondary, p.destructiveInk)
                    }
                }
            }
            if (uiState.busy && uiState.failure == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 20.dp)) {
                        Mono("loading…", KleeampType.rowSecondary, p.inkFaint)
                    }
                }
            }

            if (uiState.artists.isNotEmpty()) {
                item { SectionLabel("artists — ${uiState.artists.size}") }
                items(uiState.artists.size, key = { "ar:${uiState.artists[it].id}" }) { i ->
                    val a = uiState.artists[i]
                    ListRow(
                        onClick = { vm.onEvent(ProviderBrowseViewModel.Event.PushArtist(a.id, a.name)) },
                        verticalPadding = 11.dp,
                        trailing = {
                            if (a.albumCount > 0) Mono("${a.albumCount}", KleeampType.meta, p.inkFaint)
                        },
                    ) {
                        Mono(a.name, KleeampType.rowPrimary, p.ink, maxLines = 1)
                    }
                }
            }

            if (uiState.albums.isNotEmpty()) {
                item { SectionLabel("albums — ${uiState.albums.size}") }
                items(uiState.albums.size, key = { "al:${uiState.albums[it].id}" }) { i ->
                    val a = uiState.albums[i]
                    ListRow(
                        onClick = { vm.onEvent(ProviderBrowseViewModel.Event.PushAlbum(a.id, a.name, a.artist)) },
                        verticalPadding = 11.dp,
                        trailing = {
                            if (a.songCount > 0) Mono("${a.songCount}", KleeampType.meta, p.inkFaint)
                        },
                    ) {
                        Mono(a.name, KleeampType.rowPrimary, p.ink, maxLines = 1)
                        Mono(
                            listOfNotNull(
                                a.artist.takeIf { it.isNotBlank() },
                                a.year.takeIf { it > 0 }?.toString(),
                            ).joinToString(" · "),
                            KleeampType.rowSecondary, p.inkTertiary, maxLines = 1,
                        )
                    }
                }
            }

            if (uiState.tracks.isNotEmpty()) {
                item { SectionLabel("tracks — ${uiState.tracks.size}") }
                items(uiState.tracks.size, key = { "tr:${uiState.tracks[it].id}" }) { i ->
                    val t = uiState.tracks[i]
                    ListRow(
                        onClick = {
                            val queue = uiState.tracks.map { it.toStation(vm.account, vm.coverOf(it.id)) }
                            onPlay(queue[i], queue)
                            onOpenPlayer()
                        },
                        verticalPadding = 11.dp,
                        leading = {
                            Box(
                                Modifier.size(28.dp).clip(RoundedCornerShape(KleeampShape.tiny))
                                    .border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.tiny)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(KleeampIcons.PlayRow, null, Modifier.size(11.dp), tint = p.inkTertiary)
                            }
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Mono(clockOf(t.duration), KleeampType.meta, p.inkFaint)
                            }
                        },
                    ) {
                        Mono(t.title, KleeampType.rowPrimary, p.ink, maxLines = 1)
                        Mono(
                            listOfNotNull(
                                t.artist.takeIf { it.isNotBlank() },
                                t.album.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            KleeampType.rowSecondary, p.inkTertiary, maxLines = 1,
                        )
                    }
                }
            }

            if (!uiState.busy && uiState.failure == null && !uiState.indexState.scanning &&
                uiState.artists.isEmpty() && uiState.albums.isEmpty() && uiState.tracks.isEmpty()
            ) {
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 20.dp)) {
                        Mono("nothing here", KleeampType.rowSecondary, p.inkFaint)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun clockOf(seconds: Int): String =
    if (seconds <= 0) "" else "%d:%02d".format(seconds / 60, seconds % 60)