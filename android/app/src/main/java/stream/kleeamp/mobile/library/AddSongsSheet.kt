package stream.kleeamp.mobile.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.HairlineDivider
import stream.kleeamp.mobile.chrome.SheetDragHandle
import stream.kleeamp.mobile.chrome.SheetStatusBarIcons
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.podcasts.ShowState
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * Which list the add-songs sheet grows: a user playlist by slug, or the
 * favourites smart list. The sheet opens over whatever context asked —
 * library list, playlist detail, smart detail — and dismiss returns in
 * place. Every tap writes through immediately, so there is no done
 * control; the ticked boxes mirror live membership.
 */
sealed interface AddSongsTarget {
    data class Playlist(val slug: String) : AddSongsTarget
    data object Favorites : AddSongsTarget
}

/**
 * The song picker as a bottom sheet: local songs, stations and podcast
 * episodes in sub-tabs with one shared selection. Same chrome as every
 * other menu sheet; the tall fixed body (not the shrinking shell) hosts
 * the picker's own scrolling lists.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val p = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = p.panelRaised,
        contentColor = p.ink,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = {
            SheetStatusBarIcons()
            SheetDragHandle()
        },
    ) {
        SheetStatusBarIcons()
        Column(Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
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
            HairlineDivider(region = true)
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.72f)) {
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
            Spacer(Modifier.navigationBarsPadding().height(14.dp))
        }
    }
}
