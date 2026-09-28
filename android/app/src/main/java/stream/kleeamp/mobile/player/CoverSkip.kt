package stream.kleeamp.mobile.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.model.Station

/** Gap between pager pages, so neighbours never read as one wide cover. */
internal val CoverSkipPageGap = 14.dp

/** Side pages rest slightly smaller, so the centered cover owns the plate. */
internal const val CoverSkipSideScale = 0.92f

/**
 * Settle decision for a cover swipe. Pure so it can be unit-tested on the JVM.
 */
internal enum class CoverSkipDecision {
    None,
    Next,
    Previous,
}

/**
 * Settle math for [CoverSkip].
 *
 * @param dx finger displacement in px (negative = swipe left = next).
 * @param vx release velocity in px/s (negative = moving left).
 * @param width cover width in px.
 * @param floor minimum commit distance in px (80.dp).
 * @param fling fling commit velocity in px/s (900.dp/s).
 */
internal fun coverSkipCommit(
    dx: Float,
    vx: Float,
    width: Float,
    floor: Float,
    fling: Float,
    canNext: Boolean,
    canPrev: Boolean,
    lockedHorizontal: Boolean,
): CoverSkipDecision {
    if (!lockedHorizontal) return CoverSkipDecision.None
    if (width <= 0f) return CoverSkipDecision.None
    if (dx == 0f) return CoverSkipDecision.None
    val threshold = max(floor, 0.28f * width)
    return if (dx < 0f) {
        if (!canNext) return CoverSkipDecision.None
        if (-dx >= threshold || -vx >= fling) CoverSkipDecision.Next else CoverSkipDecision.None
    } else {
        if (!canPrev) return CoverSkipDecision.None
        if (dx >= threshold || vx >= fling) CoverSkipDecision.Previous else CoverSkipDecision.None
    }
}

/** Blocked directions never travel past ~18% of the width. */
internal fun coverSkipRubberBand(
    dx: Float,
    width: Float,
    canNext: Boolean,
    canPrev: Boolean,
): Float {
    if (width <= 0f) return 0f
    val edge = 0.18f * width
    return when {
        dx < 0f && !canNext -> (dx * 0.35f).coerceIn(-edge, 0f)
        dx > 0f && !canPrev -> (dx * 0.35f).coerceIn(0f, edge)
        else -> dx.coerceIn(-width, width)
    }
}

// Single gesture recognizer; splitting risks touch behavior.
@Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
@Composable
internal fun CoverSkip(
    current: Station?,
    previous: Station?,
    next: Station?,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val animation = remember { Animatable(0f) }
    val latestOnPrev by rememberUpdatedState(onSkipPrevious)
    val latestOnNext by rememberUpdatedState(onSkipNext)
    val latestCanPrev by rememberUpdatedState(canSkipPrevious)
    val latestCanNext by rememberUpdatedState(canSkipNext)
    val latestPrevious by rememberUpdatedState(previous)
    val latestNext by rememberUpdatedState(next)
    val latestCurrentId by rememberUpdatedState(current?.id)

    // Commit handoff: once the page-off lands the incoming cover centered at
    // full scale, the center takes it over in the same frame as the snap, so
    // the snap is invisible. Cleared once the bus confirms the new station
    // (or lands anywhere else, e.g. a transport key mid-settle).
    var swapTo by remember { mutableStateOf<Station?>(null) }
    var swapFromId by remember { mutableStateOf<String?>(null) }
    val centerStation = swapTo ?: current

    // A new cover always starts centered: after a commit we snap to 0, and an
    // external prev/next key lands here with the offset already at 0.
    val currentId = current?.id
    LaunchedEffect(currentId) {
        if (!settling && offsetX != 0f) {
            animation.snapTo(0f)
            offsetX = 0f
        }
        if (swapTo != null && currentId != swapFromId) {
            swapTo = null
            swapFromId = null
        }
    }

    fun settle(decision: CoverSkipDecision, pagePx: Float) {
        settling = true
        scope.launch {
            try {
                animation.snapTo(offsetX)
                when (decision) {
                    CoverSkipDecision.Next -> {
                        animation.animateTo(
                            -pagePx,
                            spring(dampingRatio = 0.9f, stiffness = 500f),
                        ) {
                            offsetX = value
                        }
                        // The incoming page now sits centered at full scale:
                        // hand it to the center under the snap.
                        swapTo = latestNext
                        swapFromId = latestCurrentId
                        latestOnNext()
                        animation.snapTo(0f)
                        offsetX = 0f
                    }
                    CoverSkipDecision.Previous -> {
                        animation.animateTo(
                            pagePx,
                            spring(dampingRatio = 0.9f, stiffness = 500f),
                        ) {
                            offsetX = value
                        }
                        swapTo = latestPrevious
                        swapFromId = latestCurrentId
                        latestOnPrev()
                        animation.snapTo(0f)
                        offsetX = 0f
                    }
                    CoverSkipDecision.None -> {
                        animation.animateTo(
                            0f,
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        ) {
                            offsetX = value
                        }
                        offsetX = 0f
                    }
                }
            } finally {
                offsetX = 0f
                settling = false
            }
        }
    }

    BoxWithConstraints(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                val axisLockPx = 16.dp.toPx()
                val floorPx = 80.dp.toPx()
                val flingPx = 900.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (settling) return@awaitEachGesture
                    var lockedHorizontal: Boolean? = null
                    var released = false
                    var widthPx = size.width.toFloat()
                    val tracker = VelocityTracker()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change =
                                event.changes.find { it.id == down.id } ?: break
                            if (change.isConsumed ||
                                event.changes.count { it.pressed } > 1
                            ) {
                                break
                            }
                            if (!change.pressed) {
                                released = true
                                if (lockedHorizontal == true) change.consume()
                                break
                            }
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            tracker.addPosition(change.uptimeMillis, change.position)
                            widthPx = size.width.toFloat()
                            if (lockedHorizontal == null) {
                                val slop = viewConfiguration.touchSlop
                                val lockDist = max(slop, axisLockPx)
                                if (abs(dx) < lockDist && abs(dy) < lockDist) {
                                    // A long press or the sheet can win while we wait.
                                    val finalEvent =
                                        awaitPointerEvent(PointerEventPass.Final)
                                    if (finalEvent.changes.any { it.isConsumed }) break
                                    continue
                                }
                                lockedHorizontal = abs(dx) > abs(dy)
                                if (lockedHorizontal == false) {
                                    // Vertical lock: consume nothing so the
                                    // sheet dismiss keeps its sequence.
                                    break
                                }
                            }
                            if (lockedHorizontal == true) {
                                change.consume()
                                offsetX = coverSkipRubberBand(
                                    dx,
                                    widthPx,
                                    latestCanNext,
                                    latestCanPrev,
                                )
                            }
                        }
                    } finally {
                        if (lockedHorizontal == true && (released || offsetX != 0f)) {
                            val vx = try {
                                tracker.calculateVelocity().x
                            } catch (_: Exception) {
                                0f
                            }
                            val decision = if (released) {
                                coverSkipCommit(
                                    dx = offsetX,
                                    vx = vx,
                                    width = widthPx,
                                    floor = floorPx,
                                    fling = flingPx,
                                    canNext = latestCanNext,
                                    canPrev = latestCanPrev,
                                    lockedHorizontal = true,
                                )
                            } else {
                                CoverSkipDecision.None
                            }
                            settle(decision, widthPx + CoverSkipPageGap.toPx())
                        } else {
                            offsetX = 0f
                        }
                    }
                }
            },
    ) {
        val density = LocalDensity.current
        val shiftPx = with(density) { maxWidth.toPx() + CoverSkipPageGap.toPx() }
        // Landing transition: the incoming card grows to full scale as it
        // reaches the center while the outgoing shrinks away — all driven by
        // the same finger/animation progress, so cancels glide back too.
        val progress = (offsetX / shiftPx).coerceIn(-1f, 1f)
        val centerScale = 1f - (1f - CoverSkipSideScale) * abs(progress)
        val prevScale = CoverSkipSideScale + (1f - CoverSkipSideScale) * progress.coerceIn(0f, 1f)
        val nextScale = CoverSkipSideScale + (1f - CoverSkipSideScale) * (-progress).coerceIn(0f, 1f)
        // A missing neighbour is empty ground, never an error caption: with
        // the walk as the source a null page only ever peeks out on a
        // rubber-banded (blocked) edge.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offsetX - shiftPx
                    scaleX = prevScale
                    scaleY = prevScale
                },
        ) {
            if (previous != null) {
                StationArt(station = previous, modifier = Modifier.fillMaxSize())
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offsetX
                    scaleX = centerScale
                    scaleY = centerScale
                },
        ) {
            StationArt(station = centerStation, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offsetX + shiftPx
                    scaleX = nextScale
                    scaleY = nextScale
                },
        ) {
            if (next != null) {
                StationArt(station = next, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
