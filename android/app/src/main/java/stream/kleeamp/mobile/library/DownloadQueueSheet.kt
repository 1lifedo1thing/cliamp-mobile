package stream.kleeamp.mobile.library

import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.SheetDragHandle
import stream.kleeamp.mobile.chrome.SheetStatusBarIcons
import stream.kleeamp.mobile.podcasts.DownloadStore
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * The downloading queue as a bottom sheet: the same rows as the downloads
 * list's downloading section, opened from the floating fetch key. Full
 * height like Up Next - a long queue scrolls inside instead of squeezing -
 * with the same slide, scrim, corners and swipe-down dismiss as the other
 * sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQueueSheet(
    downloads: DownloadStore,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val queue by downloads.queue.collectAsState()
    // The last row cancelled or finished: nothing left to show, so the
    // sheet peels away instead of idling empty. The key only fires on the
    // transition; opening always starts non-empty.
    LaunchedEffect(queue.isEmpty()) {
        if (queue.isEmpty()) onDismiss()
    }
    val listState = rememberLazyListState()
    val flingBehavior = ScrollableDefaults.flingBehavior()
    // Downward drags belong to the list while it can still scroll up: the
    // sheet's drag-to-dismiss must not steal them mid-list and peel away.
    // This runs before the sheet's own connection, scrolls the list by hand
    // and consumes only what moved, so a drag at the very top still falls
    // through and dismisses like every other sheet.
    val listFirst = remember(listState, flingBehavior) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                if (listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                ) {
                    return Offset.Zero
                }
                val consumed = listState.dispatchRawDelta(-available.y)
                return Offset(0f, -consumed)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // A fast downward flick must fling the list, not peel the
                // sheet: same direction rule as drags. Consumes only what
                // the list moved; the rest falls through and may dismiss.
                if (available.y <= 0f) return Velocity.Zero
                if (listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                ) {
                    return Velocity.Zero
                }
                var remaining = 0f
                with(listState) {
                    scroll {
                        with(flingBehavior) { remaining = performFling(-available.y) }
                    }
                }
                return Velocity(0f, available.y + remaining)
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = p.ground,
        contentColor = p.ink,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        // Padded below the status bar like Up Next: at full height the
        // handle must never sit under the time and notification icons,
        // and the list below it starts clear of them too.
        dragHandle = {
            SheetStatusBarIcons()
            Box(
                Modifier
                    .statusBarsPadding()
                    .padding(bottom = 2.dp),
            ) {
                SheetDragHandle()
            }
        },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        SheetStatusBarIcons()
        LazyColumn(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .nestedScroll(listFirst),
            state = listState,
        ) {
            downloadQueueSection(
                queue = queue,
                onRetryDownload = { station, auto -> downloads.download(station, auto) },
                onPauseDownload = { downloads.pause(it) },
                onResumeDownload = { downloads.resume(it) },
                onCancelDownload = { downloads.cancel(it) },
                onCancelAllDownloads = { downloads.cancelAll() },
            )
        }
    }
}
