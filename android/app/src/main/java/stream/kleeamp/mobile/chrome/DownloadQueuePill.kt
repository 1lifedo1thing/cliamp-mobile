package stream.kleeamp.mobile.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.podcasts.DownloadQueueItem
import stream.kleeamp.mobile.podcasts.DownloadState
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * Mean fetch fraction across the queue's determinate rows; negative when no
 * row knows its length. Pure so the rollup is unit-tested on the JVM.
 */
internal fun queueFraction(items: List<DownloadQueueItem>): Float {
    val known = items.mapNotNull { (it.state as? DownloadState.Active)?.fraction?.takeIf { f -> f >= 0f } }
    if (known.isEmpty()) return -1f
    return (known.sum() / known.size).coerceIn(0f, 1f)
}

/**
 * The floating fetch pill: a small rounded badge riding just above the mini
 * player while anything is downloading, with the queue size and one block
 * meter for the whole queue. Tapping slides the downloads list open; the
 * pill fades out with the queue instead of popping.
 */
@Composable
fun DownloadQueuePill(
    queue: List<DownloadQueueItem>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    AnimatedVisibility(
        visible = queue.isNotEmpty(),
        enter = fadeIn(tween(250)) + expandVertically(tween(250)),
        exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .microPress(onClick = onOpen)
                    .clip(RoundedCornerShape(18.dp))
                    .background(p.accent)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(KleeampIcons.Download, null, Modifier.size(16.dp), tint = p.onAccent)
                Mono(
                    if (queue.any { it.state is DownloadState.Active || it.state is DownloadState.Queued }) {
                        "${queue.size} fetching"
                    } else {
                        "${queue.size} failed"
                    },
                    KleeampType.meta,
                    p.onAccent,
                    maxLines = 1,
                )
                DownloadBlocks(
                    fraction = queueFraction(queue),
                    filled = p.onAccent,
                    empty = p.onAccent.copy(alpha = 0.35f),
                )
            }
        }
    }
}
