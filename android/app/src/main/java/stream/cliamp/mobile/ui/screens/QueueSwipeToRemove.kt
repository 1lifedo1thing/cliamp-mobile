package stream.cliamp.mobile.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

/** Leave vertical/diagonal touches to the list before claiming a deliberate left swipe. */
@Composable
internal fun QueueSwipeToRemove(
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val latestOnRemove by rememberUpdatedState(onRemove)
    var offset by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    val animation = remember { Animatable(0f) }

    fun settle(remove: Boolean, width: Int) {
        settling = true
        scope.launch {
            try {
                animation.snapTo(offset)
                animation.animateTo(if (remove) -width.toFloat() else 0f, tween(180)) {
                    offset = value
                }
                if (remove) latestOnRemove()
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
                                if (delta.x > 0f || -delta.x < abs(delta.y) * 2f) break
                                swiping = true
                            }
                        }
                        if (swiping) {
                            change.consume()
                            offset = (delta.x + horizontalSlop).coerceIn(-size.width.toFloat(), 0f)
                        } else {
                            // A row long press or the parent list can win while we wait.
                            val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                            if (finalEvent.changes.any { it.isConsumed }) break
                        }
                    }
                } finally {
                    if (swiping) {
                        // Distance, not fling velocity, commits a removal.
                        settle(released && -offset >= size.width * 0.4f, size.width)
                    }
                }
            }
        },
    ) {
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { alpha = if (offset < 0f) 1f else 0f }
                .background(p.destructive.copy(alpha = 0.16f))
                .padding(horizontal = Gutter)
                .clearAndSetSemantics {},
            contentAlignment = AbsoluteAlignment.CenterRight,
        ) {
            Mono("Remove", CliampType.chip, p.destructiveInk)
        }
        Box(Modifier.graphicsLayer { translationX = offset }) { content() }
    }
}
