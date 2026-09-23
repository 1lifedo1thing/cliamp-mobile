package stream.kleeamp.mobile.chrome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/** A deliberate right swipe adds the row to Up Next. The backdrop copies
 * [stream.kleeamp.mobile.player.UpNextSwipeToRemove] mirrored: a static
 * full-size accent wash with the key parked fully under the sliding item,
 * never growing with the drag. Same gesture discipline too - vertical or
 * diagonal touches belong to the list, and distance, not velocity, commits.
 * A committed swipe flies the row fully open, calls onQueue through the
 * reveal, springs the row back, and pops an Added-to-Up-Next key on top of
 * the settled row - the under-the-item reveal is the wash key; the commit
 * key rides above it, so the add is seen as well as felt. */
@Composable
fun SwipeToQueue(
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
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
                // Mirror the removal's full trip - fly all the way open so
                // the "Up Next" key is fully revealed - then spring back,
                // since the row stays put.
                animation.animateTo(if (queue) width.toFloat() else 0f, tween(180)) {
                    offset = value
                }
                if (queue) {
                    latestOnQueue()
                    confirmJob.value?.cancel()
                    confirmJob.value = scope.launch {
                        confirmAnim.snapTo(1f)
                        confirmAnim.animateTo(0f, tween(850)) { confirm = value }
                    }
                    animation.animateTo(0f, tween(220)) { offset = value }
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
                        // Initial pass: station rows live inside a
                        // HorizontalPager, which eats horizontal drags in the
                        // Main pass for page travel and overscroll. Observing
                        // here lets the row claim a deliberate right swipe
                        // before the pager ever sees it; anything else breaks
                        // unconsumed and the pager, the grid and taps behave
                        // exactly as before.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
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
                            // Below the row's slop a vertical consumer (the
                            // grid) may already own this gesture - yield to
                            // it. Horizontal consumption is the pager starting
                            // a page drag, which a deliberate row swipe
                            // preempts once past slop, so it never yields here.
                            val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                            val finalChange = finalEvent.changes.find { it.id == down.id }
                            if (finalChange != null && finalChange.isConsumed &&
                                abs(delta.y) >= abs(delta.x)
                            ) {
                                break
                            }
                        }
                    }
                } finally {
                    if (swiping) {
                        // Distance, not fling velocity, commits an add.
                        settle(released && offset >= size.width * 0.4f, size.width)
                    }
                }
            }
        },
    ) {
        // Static full-size backdrop like UpNextSwipeToRemove, mirrored: the
        // wash and the key sit fully under the sliding item for the whole
        // gesture instead of growing with the drag. The item itself is
        // opaque, so the key only ever shows in the strip the item slides
        // off of - never bleeding through the row.
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { alpha = if (offset > 0f) 1f else 0f }
                .background(p.accent.copy(alpha = 0.16f))
                .padding(horizontal = Gutter)
                .clearAndSetSemantics {},
            contentAlignment = AbsoluteAlignment.CenterLeft,
        ) {
            Mono("Up Next", KleeampType.chip, p.accent)
        }
        // The translucent shell carries the row's opacity so the wash stays
        // hidden under it for the whole gesture; the layer must wrap it so
        // the shell rides the slide instead of blanking the revealed strip.
        Box(
            Modifier
                .graphicsLayer { translationX = offset }
                .background(p.ground),
        ) { content() }
        // The confirmation: a short accent flash over the row and an
        // Added-to-Up-Next pill that pops in and fades, on top of the
        // settled item - the commit key is not under the row.
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
                Mono("Added to Up Next", KleeampType.chip, p.onAccent)
            }
        }
    }
}