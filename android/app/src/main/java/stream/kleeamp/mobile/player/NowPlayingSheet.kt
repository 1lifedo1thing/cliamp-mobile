package stream.kleeamp.mobile.player

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.media3.common.util.UnstableApi
import stream.kleeamp.mobile.chrome.SheetDragHandle
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * The expanded player as a bottom sheet: the menu's exact animation,
 * scrim, corners and handle, stretched full height. Opening lands on the
 * [Player] backstack entry (tap, mini-player drag-up, widget, tile - all
 * the old entries work unchanged); swipe-down and back dismiss through
 * [onDismiss], which pops that entry. Opening Up Next or Scope on top
 * hides the sheet until back returns, since it only shows while Player
 * is the top entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun NowPlayingSheet(
    vm: NowPlayingViewModel,
    onOpenScope: () -> Unit,
    onOpenUpNext: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = p.ground,
        contentColor = p.ink,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = { SheetDragHandle() },
        // The screen pads itself for status and navigation bars; the
        // sheet must not pad twice.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        NowPlayingScreen(
            vm = vm,
            onOpenScope = onOpenScope,
            onOpenUpNext = onOpenUpNext,
            onBack = onDismiss,
        )
    }
}
