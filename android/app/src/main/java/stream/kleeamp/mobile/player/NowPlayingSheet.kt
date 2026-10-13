package stream.kleeamp.mobile.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.media3.common.util.UnstableApi
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * The expanded player as a bottom sheet: a full-height sheet over the
 * list, which stays composed behind and shows through instead of an empty
 * page; back dismisses through [onDismiss]. Opening Up Next
 * or Scope on top hides the sheet until back returns.
 *
 * The ambient color resolves once here and feeds both the sheet container
 * and the screen, so the strip behind the drag handle wears the same tint
 * as the content below it and both melt together on a station change.
 */
@UnstableApi
@Composable
fun NowPlayingSheet(
    vm: NowPlayingViewModel,
    onOpenScope: () -> Unit,
    onOpenUpNext: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val uiState by vm.state.collectAsState()
    val ambient by rememberPlayerAmbient(uiState.shownStation)
    FullSheetShell(
        onDismiss = onDismiss,
        containerColor = sheetChromeColor(p.ground, ambient),
    ) {
        NowPlayingScreen(
            vm = vm,
            ambient = ambient,
            onOpenScope = onOpenScope,
            onOpenUpNext = onOpenUpNext,
            onBack = onDismiss,
        )
    }
}
