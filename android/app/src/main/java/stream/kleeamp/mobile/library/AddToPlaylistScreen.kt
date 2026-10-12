package stream.kleeamp.mobile.library

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
                        onBeginCreate = { creating = true },
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
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
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
                // The real favourites row, plus the picker box trailing:
                // glyph leading, title, count, then the box.
                ListRow(
                    onClick = onToggleFavorites,
                    verticalPadding = 9.dp,
                    leading = {
                        PlaylistGlyph(KleeampIcons.Heart, "favorites")
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Mono(
                                if (favoritesCount == 0) "empty" else "$favoritesCount items",
                                KleeampType.meta, p.inkFaint,
                            )
                            PickBox(
                                checked = isFavorite,
                                description = if (isFavorite) "remove from favorites" else "add to favorites",
                                onToggle = onToggleFavorites,
                            )
                        }
                    },
                ) {
                    Mono("favorites", KleeampType.rowPrimaryMedium, p.ink, maxLines = 1)
                }
            }
            item {
                SectionLabel("playlists — ${playlists.size}") {
                    // Always rendered, same reason as the library list: the
                    // dialog scrim eats taps behind it.
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
            items(playlists, key = { it.station.slug }) { pl ->
                val slug = pl.station.slug
                val isMember = slug in memberOf
                // The real playlist row, plus the picker box trailing: set
                // cover or glyph leading, title, count, then the box.
                ListRow(
                    onClick = { onToggle(slug) },
                    verticalPadding = 9.dp,
                    leading = {
                        PlaylistLeading(pl)
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Mono("${pl.songIds.size} songs", KleeampType.meta, p.inkFaint)
                            PickBox(
                                checked = isMember,
                                description = if (isMember) "remove from ${pl.station.name}" else "add to ${pl.station.name}",
                                onToggle = { onToggle(slug) },
                            )
                        }
                    },
                ) {
                    Mono(pl.station.name, KleeampType.rowPrimaryMedium, p.ink, maxLines = 1)
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
        // Naming floats centered over everything: the list never shifts.
        if (creating) {
            PlaylistNameDialog(
                title = "new playlist",
                placeholder = "name this playlist",
                onDone = onCreate,
                onDismiss = onCancelCreate,
            )
        }
    }
}
