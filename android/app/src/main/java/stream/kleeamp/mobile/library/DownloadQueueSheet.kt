package stream.kleeamp.mobile.library

import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import stream.kleeamp.mobile.player.FullSheetShell
import stream.kleeamp.mobile.podcasts.DownloadStore

/**
 * The downloading queue as a bottom sheet: the same rows as the downloads
 * list's downloading section, opened from the floating fetch key. Rides
 * the shared full-height shell — a long queue scrolls inside instead of
 * squeezing — keeping its own list-first touch arbitration below.
 */
@Composable
fun DownloadQueueSheet(
    downloads: DownloadStore,
    onDismiss: () -> Unit,
) {
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
    FullSheetShell(onDismiss = onDismiss) {
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
