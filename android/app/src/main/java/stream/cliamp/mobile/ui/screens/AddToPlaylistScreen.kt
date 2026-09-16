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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import stream.cliamp.mobile.data.PlaylistStore
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.CliampTextField
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.MainLayout
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.components.microPress
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
    var creating by rememberSaveable { mutableStateOf(false) }
    var nameText by rememberSaveable { mutableStateOf("") }

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
                    // The picker always renders, even with no playlists yet:
                    // the + button is how the first one gets made.
                    else -> PickerList(
                        listState = listState,
                        song = song,
                        playlists = ui.allPlaylists,
                        alreadyIn = ui.alreadyIn,
                        selected = ui.selected,
                        favoritesCount = ui.favoritesCount,
                        alreadyFavorite = ui.alreadyFavorite,
                        favoritesSelected = ui.favoritesSelected,
                        onToggleFavorites = { vm.onEvent(AddToPlaylistViewModel.Event.ToggleFavorites) },
                        saving = ui.saving,
                        onToggle = { vm.onEvent(AddToPlaylistViewModel.Event.Toggle(it)) },
                        onDone = { vm.onEvent(AddToPlaylistViewModel.Event.Save) },
                        creating = creating,
                        nameText = nameText,
                        onNameChange = { nameText = it },
                        onBeginCreate = { creating = true; nameText = "" },
                        onCancelCreate = { creating = false },
                        onCreate = { name ->
                            vm.onEvent(AddToPlaylistViewModel.Event.Create(name))
                            creating = false
                        },
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
    favoritesCount: Int,
    alreadyFavorite: Boolean,
    favoritesSelected: Boolean,
    onToggleFavorites: () -> Unit,
    saving: Boolean,
    onToggle: (String) -> Unit,
    onDone: () -> Unit,
    creating: Boolean,
    nameText: String,
    onNameChange: (String) -> Unit,
    onBeginCreate: () -> Unit,
    onCancelCreate: () -> Unit,
    onCreate: (String) -> Unit,
) {
    val p = LocalPalette.current
    val totalSelected = selected.size + if (favoritesSelected) 1 else 0
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Mono(song.name, CliampType.chip, p.accent, maxLines = 1, modifier = Modifier.weight(1f))
            Mono(
                if (saving) "saving…" else "$totalSelected selected",
                CliampType.meta, p.inkTertiary,
            )
            Chip(if (saving) "saving" else "done", selected = false, onClick = onDone)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            item {
                ListRow(
                    onClick = onToggleFavorites,
                    verticalPadding = 9.dp,
                    leading = {
                        Box(
                            Modifier.size(24.dp).clip(RoundedCornerShape(CliampShape.tiny))
                                .then(
                                    if (favoritesSelected) Modifier.background(p.accent)
                                    else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.tiny))
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (favoritesSelected) Icon(CliampIcons.Check, null, Modifier.size(10.dp), tint = p.onAccent)
                        }
                    },
                ) {
                    Mono("favorites", CliampType.rowPrimary, p.ink, maxLines = 1)
                    Mono(
                        buildList {
                            add("$favoritesCount items")
                            if (alreadyFavorite) add("already added")
                        }.joinToString(" · "),
                        CliampType.rowSecondary, p.inkTertiary, maxLines = 1,
                    )
                }
            }
            item {
                SectionLabel("playlists — ${playlists.size}") {
                    if (!creating) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(CliampShape.small))
                                .background(if (p.dark) p.keyFace else p.ground)
                                .border(1.dp, p.keyBorder, RoundedCornerShape(CliampShape.small))
                                .microPress(onClick = onBeginCreate),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(CliampIcons.Plus, "new playlist", Modifier.size(16.dp), tint = p.accent)
                        }
                    }
                }
            }
            if (creating) {
                item {
                    PlaylistNameField(
                        text = nameText,
                        onTextChange = onNameChange,
                        placeholder = "name this playlist",
                        onDone = onCreate,
                        onCancel = onCancelCreate,
                    )
                }
            }
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

/**
 * The inline playlist naming row, the same shape as the library list's: a
 * field plus SAVE/CANCEL. It never grabs focus on its own; the keyboard only
 * comes up when the user taps the field.
 */
@Composable
private fun PlaylistNameField(
    text: String,
    placeholder: String,
    onTextChange: (String) -> Unit,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(Gutter)
            .clip(RoundedCornerShape(CliampShape.small)).border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.small))
            .background(p.panel).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CliampTextField(
            value = text,
            onValueChange = { onTextChange(it.take(48)) },
            modifier = Modifier.weight(1f),
            placeholder = placeholder,
            textStyle = CliampType.rowPrimary,
            onAction = { onDone(text) },
        )
        Mono("SAVE", CliampType.tabLabel, p.accent,
            Modifier.clip(RoundedCornerShape(CliampShape.tiny)).background(p.accent.copy(alpha = 0.14f))
                .microPress { onDone(text) }.padding(horizontal = 9.dp, vertical = 7.dp))
        Mono("CANCEL", CliampType.tabLabel, p.inkTertiary,
            Modifier.clip(RoundedCornerShape(CliampShape.tiny)).border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.tiny))
                .microPress(onClick = onCancel).padding(horizontal = 9.dp, vertical = 7.dp))
    }
}
