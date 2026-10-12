package stream.kleeamp.mobile.player

import androidx.compose.runtime.Composable
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.prefs.Prefs

/**
 * The scope (visualizer plus equalizer) as a bottom sheet stacked over
 * the player sheet. The sheets underneath stay open in place — nothing
 * closes just to reopen on back; back peels one layer.
 */
@Composable
fun ScopeSheet(
    prefs: Prefs,
    station: Station?,
    streamTitle: String,
    playing: Boolean,
    onDismiss: () -> Unit,
) {
    FullSheetShell(onDismiss = onDismiss) {
        ScopeScreen(
            prefs = prefs,
            station = station,
            streamTitle = streamTitle,
            playing = playing,
            onBack = onDismiss,
        )
    }
}
