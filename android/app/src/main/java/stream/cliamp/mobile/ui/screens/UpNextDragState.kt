package stream.cliamp.mobile.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import stream.cliamp.mobile.playback.upNextIndices
import stream.cliamp.mobile.data.Station

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

/** A drag previews the new order locally; only dropping commits an edit to playback. */
internal class UpNextDragState(
    private val initialEntries: List<UpNextEntry>,
    private val listState: LazyListState,
    private val scope: CoroutineScope,
) {
    var entries by mutableStateOf(initialEntries)
        private set
    var draggingKey by mutableStateOf<String?>(null)
        private set
    var settlingKey by mutableStateOf<String?>(null)
        private set
    val settlingOffset = Animatable(0f)
    private var settleJob: Job? = null
    private var dragTop by mutableFloatStateOf(0f)
    private var pointerY = 0f
    private var headerCount = 0

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

    fun finish(onMove: (Int, Int) -> Unit) {
        val key = draggingKey ?: return
        val destination = entries.indexOfFirst { it.key == key }
        val source = initialEntries.first { it.key == key }.upNextIndex
        val target = initialEntries[destination].upNextIndex
        val offset = dragOffset
        draggingKey = null
        settlingKey = key
        settleJob = scope.launch {
            settlingOffset.snapTo(offset)
            settlingOffset.animateTo(0f)
            settlingKey = null
        }
        if (source != target) onMove(source, target)
    }

    fun cancel() {
        draggingKey = null
        entries = initialEntries
    }
}
