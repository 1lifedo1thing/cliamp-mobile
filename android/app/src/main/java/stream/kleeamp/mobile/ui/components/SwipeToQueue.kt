package stream.kleeamp.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.ui.theme.KleeampShape
import stream.kleeamp.mobile.ui.theme.KleeampType
import stream.kleeamp.mobile.ui.theme.LocalPalette
import stream.kleeamp.mobile.ui.theme.Mono

/**
 * A deliberate right swipe adds the row to the queue. Same gesture discipline
 * as [stream.kleeamp.mobile.ui.screens.UpNextSwipeToRemove]: vertical or
 * diagonal touches belong to the list, and distance, not velocity, commits.
 * A committed swipe flashes the row and pulses a QUEUED key, so the add is
 * felt as well as counted.
 */
@Composable
fun SwipeToQueue(
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val latestOnQueue by rememberUpdatedState(onQueue)
    var offset by remember { mutableFloatStateOf(0f) }
    var confirm by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    val animation = remember { Animatable(0f) }
    val confirmAnim = remember { Animatable(0f) }
    val confirmJob = remember { mutableStateOf<Job?>(null) }

    fun settle(queue: Boolean, width: Int) {
        settling = true
        scope.launch {
            try {
                animation.snapTo(offset)
                if (queue) {
                    latestOnQueue()
                    confirmJob.value?.cancel()
                    confirmJob.value = scope.launch {
                        confirmAnim.snapTo(1f)
                        confirmAnim.animateTo(0f, tween(850)) { confirm = value }
                    }
                    animation.animateTo(0f, tween(220)) { offset = value }
                } else {
                    animation.animateTo(0f, tween(180)) { offset = value }
                }
            } finally {
                offset = 0f
                settling = false
            }
        }
    }

    Box(
        modifier.clipToBounds().pointerInput(Unit) {
            val horizontalSlop = maxOf(24.dp.toPx(), viewConfiguration.touchSlop * 2f)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (settling) return@awaitEachGesture
                var swiping = false
                var released = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.find { it.id == down.id } ?: break
                        if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                        if (!change.pressed) {
                            released = true
                            if (swiping) change.consume()
                            break
                        }
                        val delta = change.position - down.position
                        if (!swiping) {
                            // Decide once: even a small sideways start must not steal a scroll.
                            if (abs(delta.y) > viewConfiguration.touchSlop) break
                            if (abs(delta.x) > horizontalSlop) {
                                if (delta.x < 0f || delta.x < abs(delta.y) * 2f) break
                                swiping = true
                                confirmJob.value?.cancel()
                                confirm = 0f
                            }
                        }
                        if (swiping) {
                            change.consume()
                            offset = (delta.x - horizontalSlop).coerceIn(0f, size.width.toFloat())
                        } else {
                            // A row long press or the parent list can win while we wait.
                            val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                            if (finalEvent.changes.any { it.isConsumed }) break
                        }
                    }
                } finally {
                    if (swiping) settle(released && offset >= size.width * 0.4f, size.width)
                }
            }
        },
    ) {
        Box(Modifier.matchParentSize().clearAndSetSemantics {}) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(with(density) { offset.toDp() })
                    .background(p.accent.copy(alpha = 0.16f)),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = Gutter)
                    .graphicsLayer { alpha = if (offset > 0f) 1f else 0f },
                contentAlignment = Alignment.CenterStart,
            ) {
                Mono("Queue", KleeampType.chip, p.accent)
            }
        }
        Box(Modifier.graphicsLayer { translationX = offset }) { content() }
        // The confirmation: a short accent flash over the row and an
        // ADDED TO QUEUE pill that pops in and fades, so the add is seen.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = confirm * 0.14f }
                .background(p.accent)
                .clearAndSetSemantics {},
        )
        Box(
            Modifier.matchParentSize(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .graphicsLayer {
                        alpha = confirm
                        scaleX = 0.82f + 0.18f * confirm
                        scaleY = 0.82f + 0.18f * confirm
                    }
                    .clip(RoundedCornerShape(KleeampShape.small))
                    .background(p.accent)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .clearAndSetSemantics {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mono("ADDED TO QUEUE", KleeampType.chip, p.onAccent)
            }
        }
    }
}
