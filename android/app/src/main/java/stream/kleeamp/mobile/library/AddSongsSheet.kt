package stream.kleeamp.mobile.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.MenuSheetShell
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.podcasts.ShowState
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * Which list the add-songs sheet grows: a user playlist by slug, or the
 * favourites smart list. The sheet opens over whatever context asked —
 * library list, playlist detail, favourites detail — and dismiss returns in
 * place. Every tap writes through immediately, so there is no done
 * control; the ticked boxes mirror live membership.
 */
sealed interface AddSongsTarget {
    data class Playlist(val slug: String) : AddSongsTarget
    data object Favorites : AddSongsTarget
}

/**
 * The song picker as a bottom sheet: local songs, stations and podcast
 * episodes in sub-tabs with one shared selection. Rides the shared menu
 * shell with a tall fixed body and no shell scroll — the picker's own
 * lists scroll themselves, and a scroll inside a scroll would break them.
 */
@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
fun AddSongsSheet(
    playlistName: String,
    selected: Set<String>,
    localSongs: List<Station>,
    radioStations: List<Station>,
    subscribedShows: List<PodcastShow>,
    showState: ShowState,
    onOpenShow: (PodcastShow) -> Unit,
    onToggle: (Station) -> Unit,
    onDismiss: () -> Unit,
) {
    MenuSheetShell(
        onDismiss = onDismiss,
        bodyFraction = 0.72f,
        scrollBody = false,
        header = {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Mono("add songs", KleeampType.rowSecondary, LocalPalette.current.inkTertiary, maxLines = 1)
                    Mono(
                        playlistName.ifBlank { "unknown" },
                        KleeampType.trackTitleCompact,
                        LocalPalette.current.ink,
                        maxLines = 1,
                    )
                }
            }
        },
    ) {
        AddSongsPicker(
            selected = selected,
            playlistName = playlistName,
            showTitle = false,
            localSongs = localSongs,
            radioStations = radioStations,
            subscribedShows = subscribedShows,
            showState = showState,
            onOpenShow = onOpenShow,
            onToggle = onToggle,
        )
    }
}
