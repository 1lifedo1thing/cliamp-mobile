package stream.kleeamp.mobile.library

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.SheetDragHandle
import stream.kleeamp.mobile.podcasts.DownloadStore
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * The downloading queue as a bottom sheet: the same rows as the downloads
 * list's downloading section, opened from the floating fetch key. Same
 * slide, scrim, corners and swipe-down dismiss as the other sheets.
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = p.ground,
        contentColor = p.ink,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = { SheetDragHandle() },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        LazyColumn(
            Modifier.navigationBarsPadding(),
            state = rememberLazyListState(),
        ) {
            downloadQueueSection(
                queue = queue,
                onRetryDownload = { station, auto -> downloads.download(station, auto) },
                onCancelDownload = { downloads.cancel(it) },
                onCancelAllDownloads = { downloads.cancelAll() },
            )
        }
    }
}
