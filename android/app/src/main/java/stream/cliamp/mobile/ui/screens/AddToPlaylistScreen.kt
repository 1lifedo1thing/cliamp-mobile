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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import stream.cliamp.mobile.data.PlaylistStore
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.MainLayout
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.components.scrollToTop
import stream.cliamp.mobile.ui.theme.CliampShape
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

/**
 * The add-to-playlist page: one song, every user playlist, multi-select.
 * Rows toggle on tap (tap again to unselect); Done fans the song out to all
 * selected playlists and pops. Playlists that already hold the song say so
 * and stay toggleable — Done simply leaves them untouched.
 */
@Composable
fun LibraryAddToPlaylistPane(
    vm: AddToPlaylistViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val p = LocalPalette.current
    val ui by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(ui.saved) {
        if (ui.saved) onDone()
    }

    Box(Modifier.fillMaxSize().background(p.ground)) {
        MainLayout(
            title = "add to playlist",
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(listState) },
            onBack = onBack,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val song = ui.song
                when {
                    !ui.songReady -> Note("finding that song…")
                    song == null -> Note("song gone")
                    ui.allPlaylists.isEmpty() ->
                        Note("no playlists yet — make one in the library first")
                    else -> PickerList(
                        listState = listState,
                        song = song,
                        playlists = ui.allPlaylists,
                        alreadyIn = ui.alreadyIn,
                        selected = ui.selected,
                        saving = ui.saving,
                        onToggle = { vm.onEvent(AddToPlaylistViewModel.Event.Toggle(it)) },
                        onDone = { vm.onEvent(AddToPlaylistViewModel.Event.Save) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Mono(text, CliampType.rowSecondary, p.inkFaint)
    }
}

@Composable
private fun PickerList(
    listState: androidx.compose.foundation.lazy.LazyListState,
    song: Station,
    playlists: List<PlaylistStore.Playlist>,
    alreadyIn: Set<String>,
    selected: Set<String>,
    saving: Boolean,
    onToggle: (String) -> Unit,
    onDone: () -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Mono(song.name, CliampType.chip, p.accent, maxLines = 1, modifier = Modifier.weight(1f))
            Mono(
                if (saving) "saving…" else "${selected.size} selected",
                CliampType.meta, p.inkTertiary,
            )
            Chip(if (saving) "saving" else "done", selected = false, onClick = onDone)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            item { SectionLabel("playlists — ${playlists.size}") }
            items(playlists, key = { it.station.slug }) { pl ->
                val slug = pl.station.slug
                val isSelected = slug in selected
                ListRow(
                    onClick = { onToggle(slug) },
                    verticalPadding = 9.dp,
                    leading = {
                        Box(
                            Modifier.size(24.dp).clip(RoundedCornerShape(CliampShape.tiny))
                                .then(
                                    if (isSelected) Modifier.background(p.accent)
                                    else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.tiny))
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) Icon(CliampIcons.Check, null, Modifier.size(10.dp), tint = p.onAccent)
                        }
                    },
                ) {
                    Mono(pl.station.name, CliampType.rowPrimary, p.ink, maxLines = 1)
                    Mono(
                        buildList {
                            add("${pl.songIds.size} songs")
                            if (slug in alreadyIn) add("already added")
                        }.joinToString(" · "),
                        CliampType.rowSecondary, p.inkTertiary, maxLines = 1,
                    )
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
