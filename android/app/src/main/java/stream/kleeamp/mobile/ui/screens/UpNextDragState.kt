package stream.kleeamp.mobile.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.playback.upNextIndices
import stream.kleeamp.mobile.model.Station

internal data class UpNextEntry(val key: String, val upNextIndex: Int, val station: Station)

/** An occurrence key allows the same song to appear in Up Next more than once. */
internal fun upNextEntries(upNext: List<Station>, activeIndex: Int): List<UpNextEntry> {
    val upcoming = upNextIndices(upNext.size, activeIndex)
    val occurrences = mutableMapOf<String, Int>()
    return upNext.mapIndexed { index, station ->
        val occurrence = occurrences.getOrDefault(station.url, 0)
        occurrences[station.url] = occurrence + 1
        UpNextEntry("${station.url}#$occurrence", index, station)
    }.filter { it.upNextIndex in upcoming }
}

/**
 * A drag previews the new order locally; only dropping commits an edit to
 * playback. One instance survives queue emissions: [sync] merges the latest
 * committed entries into the preview instead of resetting it, so a track
 * change mid-drag no longer drops the finger's item or its position.
 */
internal class UpNextDragState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
) {
    var entries by mutableStateOf(emptyList<UpNextEntry>())
        private set
    /** Last committed order; the preview diffs against this on drop. */
    private var base by mutableStateOf(emptyList<UpNextEntry>())
    var draggingKey by mutableStateOf<String?>(null)
        private set
    var settlingKey by mutableStateOf<String?>(null)
        private set
    val settlingOffset = Animatable(0f)
    private var settleJob: Job? = null
    private var dragTop by mutableFloatStateOf(0f)
    private var pointerY = 0f
    private var headerCount = 0

    /**
     * Fold the latest committed entries in. Idle, this is a plain reset, so
     * row taps and a11y actions always resolve against current indices.
     * Mid-drag, the preview order wins for surviving keys, vanished keys
     * drop out, and arrivals append - and a drag whose item is gone ends.
     */
    fun sync(committed: List<UpNextEntry>) {
        if (draggingKey == null) {
            entries = committed
            base = committed
            return
        }
        val surviving = entries.filter { e -> committed.any { it.key == e.key } }
        val arrivals = committed.filter { c -> surviving.none { it.key == c.key } }
        entries = surviving + arrivals
        base = committed
        if (draggingKey !in entries.map { it.key }) cancel()
    }

    val dragOffset: Float
        get() = listState.layoutInfo.visibleItemsInfo.find { it.key == draggingKey }
            ?.let { dragTop - it.offset } ?: 0f

    fun start(key: String, localY: Float): Boolean {
        val hit = listState.layoutInfo.visibleItemsInfo.find { it.key == key } ?: return false
        val y = hit.offset + localY
        settleJob?.cancel()
        settlingKey = null
        draggingKey = key
        headerCount = hit.index - entries.indexOfFirst { it.key == hit.key }
        dragTop = hit.offset.toFloat()
        pointerY = y
        return true
    }

    fun drag(delta: Float) {
        if (draggingKey == null) return
        dragTop += delta
        pointerY += delta
        val visible = listState.layoutInfo.visibleItemsInfo
        val item = visible.find { it.key == draggingKey } ?: return
        val from = entries.indexOfFirst { it.key == draggingKey }
        // Wait for the last preview move to be laid out before moving again.
        if (item.index != headerCount + from) return
        val center = dragTop + item.size / 2f
        val target = visible.find {
            it.key != draggingKey && center >= it.offset && center < it.offset + it.size &&
                entries.any { entry -> entry.key == it.key }
        } ?: return
        val to = entries.indexOfFirst { it.key == target.key }
        // Keep the viewport anchored when its first visible row changes places.
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        entries = entries.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Pixels/second; the frame loop continues even when a finger rests near an edge. */
    fun scrollSpeed(edge: Float): Float {
        if (draggingKey == null) return 0f
        val layout = listState.layoutInfo
        val top = layout.viewportStartOffset + edge
        val bottom = layout.viewportEndOffset - edge
        return when {
            pointerY < top -> -((top - pointerY) / edge).coerceIn(0f, 1f) * edge * 12f
            pointerY > bottom -> ((pointerY - bottom) / edge).coerceIn(0f, 1f) * edge * 12f
            else -> 0f
        }
    }

    /**
     * Commit the drop as one move in player terms: the dragged entry's own
     * [UpNextEntry.upNextIndex] is the source, and the entry holding the drop
     * slot in the latest committed order names the destination. Entries carry
     * true player indices because the preview shows only the upcoming window,
     * so filtered positions must never reach the player. A key that vanished
     * mid-drag commits nothing instead of throwing; an unchanged position
     * commits nothing either.
     */
    fun finish(onMove: (Int, Int) -> Unit) {
        val key = draggingKey ?: return
        val offset = dragOffset
        draggingKey = null
        val snapshot = base.toList()
        val source = snapshot.firstOrNull { it.key == key }?.upNextIndex ?: return
        val destPos = entries.indexOfFirst { it.key == key }
        settlingKey = key
        settleJob = scope.launch {
            settlingOffset.snapTo(offset)
            settlingOffset.animateTo(0f)
            settlingKey = null
        }
        if (destPos < 0) return
        val to = snapshot.getOrNull(destPos)?.upNextIndex ?: return
        if (source != to) onMove(source, to)
    }

    fun cancel() {
        draggingKey = null
        entries = base
    }
}
