package stream.kleeamp.mobile.player

import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.playback.PlayerConnection

/**
 * Up Next as a bottom sheet stacked over the player sheet, so its
 * previous page is always Now Playing. Back dismisses through
 * [onDismiss]; the player sheet underneath stays open.
 */
@UnstableApi
@Composable
fun UpNextSheet(
    player: PlayerConnection,
    current: Station?,
    playing: Boolean,
    onPlay: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    FullSheetShell(onDismiss = onDismiss) {
        UpNextScreen(
            player = player,
            current = current,
            playing = playing,
            onPlay = onPlay,
            onBack = onDismiss,
        )
    }
}
