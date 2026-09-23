package stream.kleeamp.mobile.ui.screens

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
import stream.kleeamp.mobile.data.PlaylistStore
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.KleeampTextField
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.MainLayout
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.chrome.scrollToTop
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * The add-to-playlist page: one song, favourites plus every user playlist.
 * There is no Done — every tap adds or removes immediately, and back just
 * leaves. A ticked row means the song is in there right now.
 */
@Composable
fun LibraryAddToPlaylistPane(
    vm: AddToPlaylistViewModel,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val p = LocalPalette.current
    val ui by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var creating by rememberSaveable { mutableStateOf(false) }
    var nameText by rememberSaveable { mutableStateOf("") }

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
                        memberOf = ui.memberOf,
                        favoritesCount = ui.favoritesCount,
                        isFavorite = ui.isFavorite,
                        onToggleFavorites = { vm.onEvent(AddToPlaylistViewModel.Event.ToggleFavorites) },
                        onToggle = { vm.onEvent(AddToPlaylistViewModel.Event.Toggle(it)) },
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
        Mono(text, KleeampType.rowSecondary, p.inkFaint)
    }
}

@Composable
private fun PickerList(
    listState: androidx.compose.foundation.lazy.LazyListState,
    song: Station,
    playlists: List<PlaylistStore.Playlist>,
    memberOf: Set<String>,
    favoritesCount: Int,
    isFavorite: Boolean,
    onToggleFavorites: () -> Unit,
    onToggle: (String) -> Unit,
    creating: Boolean,
    nameText: String,
    onNameChange: (String) -> Unit,
    onBeginCreate: () -> Unit,
    onCancelCreate: () -> Unit,
    onCreate: (String) -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Mono(song.name, KleeampType.chip, p.accent, maxLines = 1, modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            item {
                ListRow(
                    onClick = onToggleFavorites,
                    verticalPadding = 9.dp,
                    leading = {
                        Box(
                            Modifier.size(24.dp).clip(RoundedCornerShape(KleeampShape.tiny))
                                .then(
                                    if (isFavorite) Modifier.background(p.accent)
                                    else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.tiny))
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isFavorite) Icon(KleeampIcons.Check, null, Modifier.size(10.dp), tint = p.onAccent)
                        }
                    },
                ) {
                    Mono("favorites", KleeampType.rowPrimary, p.ink, maxLines = 1)
                    Mono(
                        "$favoritesCount items",
                        KleeampType.rowSecondary, p.inkTertiary, maxLines = 1,
                    )
                }
            }
            item {
                SectionLabel("playlists — ${playlists.size}") {
                    if (!creating) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(KleeampShape.small))
                                .background(if (p.dark) p.keyFace else p.ground)
                                .border(1.dp, p.keyBorder, RoundedCornerShape(KleeampShape.small))
                                .microPress(onClick = onBeginCreate),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(KleeampIcons.Plus, "new playlist", Modifier.size(16.dp), tint = p.accent)
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
                val isMember = slug in memberOf
                ListRow(
                    onClick = { onToggle(slug) },
                    verticalPadding = 9.dp,
                    leading = {
                        Box(
                            Modifier.size(24.dp).clip(RoundedCornerShape(KleeampShape.tiny))
                                .then(
                                    if (isMember) Modifier.background(p.accent)
                                    else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.tiny))
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isMember) Icon(KleeampIcons.Check, null, Modifier.size(10.dp), tint = p.onAccent)
                        }
                    },
                ) {
                    Mono(pl.station.name, KleeampType.rowPrimary, p.ink, maxLines = 1)
                    Mono(
                        "${pl.songIds.size} songs",
                        KleeampType.rowSecondary, p.inkTertiary, maxLines = 1,
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
            .clip(RoundedCornerShape(KleeampShape.small)).border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.small))
            .background(p.panel).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        KleeampTextField(
            value = text,
            onValueChange = { onTextChange(it.take(48)) },
            modifier = Modifier.weight(1f),
            placeholder = placeholder,
            textStyle = KleeampType.rowPrimary,
            onAction = { onDone(text) },
        )
        Mono("SAVE", KleeampType.tabLabel, p.accent,
            Modifier.clip(RoundedCornerShape(KleeampShape.tiny)).background(p.accent.copy(alpha = 0.14f))
                .microPress { onDone(text) }.padding(horizontal = 9.dp, vertical = 7.dp))
        Mono("CANCEL", KleeampType.tabLabel, p.inkTertiary,
            Modifier.clip(RoundedCornerShape(KleeampShape.tiny)).border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.tiny))
                .microPress(onClick = onCancel).padding(horizontal = 9.dp, vertical = 7.dp))
    }
}
