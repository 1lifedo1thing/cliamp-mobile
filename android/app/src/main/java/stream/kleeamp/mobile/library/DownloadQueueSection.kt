package stream.kleeamp.mobile.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.DownloadBlocks
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.podcasts.DownloadQueueItem
import stream.kleeamp.mobile.podcasts.DownloadState
import stream.kleeamp.mobile.podcasts.downloadSizeLabel
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * The downloading view, shared by the downloads list and the queue sheet:
 * every active, queued and failed fetch with its status. Retry and cancel
 * ride the rows; cancel all clears the whole queue.
 */
fun LazyListScope.downloadQueueSection(
    queue: List<DownloadQueueItem>,
    onRetryDownload: (Station, Boolean) -> Unit,
    onCancelDownload: (String) -> Unit,
    onCancelAllDownloads: () -> Unit,
) {
    if (queue.isEmpty()) return
    item {
        DownloadQueueHeader(count = queue.size, onCancelAll = onCancelAllDownloads)
    }
    items(queue, key = { it.url }, contentType = { "download-queue" }) { q ->
        DownloadQueueRow(
            item = q,
            onRetry = { onRetryDownload(q.station, q.auto) },
            onCancel = { onCancelDownload(q.url) },
        )
    }
}

@Composable
private fun DownloadQueueHeader(count: Int, onCancelAll: () -> Unit) {
    val p = LocalPalette.current
    SectionLabel("downloading — $count") {
        Mono(
            "cancel all",
            KleeampType.meta,
            p.inkTertiary,
            Modifier.microPress(onClick = onCancelAll),
        )
    }
}

@Composable
private fun DownloadQueueRow(
    item: DownloadQueueItem,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val p = LocalPalette.current
    ListRow(
        onClick = null,
        verticalPadding = 9.dp,
        leading = { SongCover(s = item.station, current = null, playing = false) },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (item.state is DownloadState.Active) {
                    DownloadBlocks(item.state.fraction)
                }
                if (item.state is DownloadState.Failed) {
                    Mono(
                        "retry",
                        KleeampType.meta,
                        p.accent,
                        Modifier.microPress(onClick = onRetry),
                    )
                }
                Mono(
                    "cancel",
                    KleeampType.meta,
                    p.inkTertiary,
                    Modifier.microPress(onClick = onCancel),
                )
            }
        },
    ) {
        Mono(item.station.name, KleeampType.rowPrimary, p.ink, maxLines = 1)
        Mono(
            when (val s = item.state) {
                is DownloadState.Active ->
                    if (s.indeterminate) "fetching ${downloadSizeLabel(s.bytesRead)}"
                    else "fetching ${(s.fraction * 100).toInt()}%"
                is DownloadState.Queued -> "queued"
                is DownloadState.Failed -> s.reason
                is DownloadState.Idle -> ""
            },
            KleeampType.rowSecondary,
            if (item.state is DownloadState.Failed) p.destructiveInk else p.inkTertiary,
            maxLines = 1,
        )
    }
}
