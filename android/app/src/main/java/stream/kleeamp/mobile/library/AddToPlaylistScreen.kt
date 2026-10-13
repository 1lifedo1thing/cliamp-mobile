package stream.kleeamp.mobile.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.MenuSheetShell
import stream.kleeamp.mobile.chrome.StationMenuArt
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * The add-to-playlist picker as a bottom sheet: the song stays underneath,
 * favourites plus every user playlist with its cover (or glyph when
 * coverless). Every tap adds or removes immediately and the sheet stays open
 * for multi-pick; swipe-down or scrim tap dismisses. A ticked row means the
 * song is in there right now.
 */
@Composable
fun AddToPlaylistSheet(
    song: Station,
    vm: AddToPlaylistViewModel,
    onDismiss: () -> Unit,
) {
    val ui by vm.state.collectAsState()

    MenuSheetShell(
        onDismiss = onDismiss,
        halfHeight = true,
        header = {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(52.dp)) { StationMenuArt(song) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Mono("add to playlist", KleeampType.rowSecondary, LocalPalette.current.inkTertiary, maxLines = 1)
                    Mono(song.name.ifBlank { "unknown" }, KleeampType.trackTitleCompact, LocalPalette.current.ink, maxLines = 2)
                }
            }
        },
    ) {
        PickerRow(
            label = "favorites",
            countLabel = if (ui.favoritesCount == 0) "empty" else "${ui.favoritesCount} items",
            checked = ui.isFavorite,
            checkDescription = if (ui.isFavorite) "remove from favorites" else "add to favorites",
            leading = { PlaylistGlyph(KleeampIcons.Heart, "favorites") },
            onClick = { vm.onEvent(AddToPlaylistViewModel.Event.ToggleFavorites) },
        )
        if (ui.allPlaylists.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                Mono("no playlists yet", KleeampType.rowSecondary, LocalPalette.current.inkFaint)
            }
        } else {
            ui.allPlaylists.forEach { pl ->
                val slug = pl.station.slug
                val isMember = slug in ui.memberOf
                PickerRow(
                    label = pl.station.name,
                    countLabel = "${pl.songIds.size} songs",
                    checked = isMember,
                    checkDescription = if (isMember) "remove from ${pl.station.name}" else "add to ${pl.station.name}",
                    leading = { PlaylistLeading(pl) },
                    onClick = { vm.onEvent(AddToPlaylistViewModel.Event.Toggle(slug)) },
                )
            }
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    countLabel: String,
    checked: Boolean,
    checkDescription: String,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .microPress(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Mono(label, KleeampType.rowPrimary, p.ink, maxLines = 1)
            Mono(countLabel, KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
        }
        PickBox(checked = checked, description = checkDescription, onToggle = onClick)
    }
}
