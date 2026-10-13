package stream.kleeamp.mobile.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Gap between pager pages, so neighbours never read as one wide cover. */
internal val CoverSkipPageGap = 14.dp

/**
 * Settle decision for a pager swipe. Pure so it can be unit-tested on the JVM.
 */
internal enum class CoverSkipDecision {
    None,
    Next,
    Previous,
}

/**
 * Settle math for [SkipPager].
 *
 * @param dx finger displacement in px (negative = swipe left = next).
 * @param vx release velocity in px/s (negative = moving left).
 * @param width page width in px.
 * @param floor minimum commit distance in px.
 * @param fling fling commit velocity in px/s.
 * @param fraction commit distance as a share of [width]; the cover wants
 *   0.28, the meter strip keeps its old easy 0.2.
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
    fraction: Float = 0.28f,
): CoverSkipDecision {
    if (!lockedHorizontal) return CoverSkipDecision.None
    if (width <= 0f) return CoverSkipDecision.None
    if (dx == 0f) return CoverSkipDecision.None
    val threshold = max(floor, fraction * width)
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

/** Holder for the active settle job, so a new drag can take over mid-settle. */
private class SettleCtl {
    var job: Job? = null
}

/**
 * The shared swipe pager behind the Now Playing cover ([CoverSkip]) and the
 * visualizer meter ([VisSkip]): the drag follows the finger with the
 * neighbours gliding in from the sides, and releasing under the threshold
 * (or a cancelled gesture) slides back instead of paging.
 *
 * Touch stays on the well-behaved [detectHorizontalDragGestures] detector -
 * the same one the old fire-on-release swipe used - so taps, double-tap
 * fullscreen, the landscape pane scroll and the sheet dismiss all keep
 * working. Releases resolve fast, like the old fixed slide: a commit slides
 * the page off in 180ms, a cancel slides back in 150ms.
 *
 * @param currentId identity of [current], for resetting after external
 *   changes (transport keys, scope chips) land mid-gesture.
 */
@Composable
internal fun <T> SkipPager(
    current: T,
    previous: T?,
    next: T?,
    currentId: String?,
    canGoNext: Boolean,
    canGoPrevious: Boolean,
    onGoNext: () -> Unit,
    onGoPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    commitFraction: Float = 0.28f,
    commitFloor: Dp = 80.dp,
    flingSpeed: Dp = 900.dp,
    pageGap: Dp = CoverSkipPageGap,
    pageModifier: Modifier = Modifier.fillMaxSize(),
    pageContent: @Composable BoxScope.(page: T) -> Unit,
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val animation = remember { Animatable(0f) }
    val latestOnPrev by rememberUpdatedState(onGoPrevious)
    val latestOnNext by rememberUpdatedState(onGoNext)
    val latestPrevious by rememberUpdatedState(previous)
    val latestNext by rememberUpdatedState(next)
    val latestCurrentId by rememberUpdatedState(currentId)
    // Read without restarting the detector: keying pointerInput on settling
    // restarts gesture tracking mid-touch, which drops the active drag - its
    // moves then go unconsumed, the sheet eats the leaked vertical noise and
    // dips, and the touch reads as dead. These stay live across restarts.
    val settlingRef = rememberUpdatedState(settling)
    val settleCtl = remember { SettleCtl() }
    // canGo flags flow here too: station ends change without any pointerInput
    // key changing, and a stale flag would rubber-band or commit wrong.
    val latestCanNext by rememberUpdatedState(canGoNext)
    val latestCanPrev by rememberUpdatedState(canGoPrevious)

    // Commit handoff: once the page-off lands the incoming page centered,
    // the center takes it over in the same frame as the snap, so the snap is
    // invisible. Cleared once the commit flows back as the new current (or
    // lands anywhere else, e.g. a transport key mid-settle).
    var swapTo by remember { mutableStateOf<T?>(null) }
    var swapFromId by remember { mutableStateOf<String?>(null) }
    val centerPage = swapTo ?: current

    // A new page always starts centered: after a commit we snap to 0, and an
    // external change lands here with the offset already at 0.
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
        settleCtl.job?.cancel()
        settling = true
        lateinit var me: Job
        me = scope.launch {
            try {
                animation.snapTo(offsetX)
                when (decision) {
                    CoverSkipDecision.Next -> {
                        animation.animateTo(
                            -pagePx,
                            tween(180, easing = FastOutSlowInEasing),
                        ) {
                            offsetX = value
                        }
                        // The incoming page now sits centered:
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
                            tween(180, easing = FastOutSlowInEasing),
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
                            tween(150, easing = FastOutSlowInEasing),
                        ) {
                            offsetX = value
                        }
                        offsetX = 0f
                    }
                }
            } finally {
                // A takeover or a newer settle owns the pager now: only the
                // latest settle may park it back at rest, otherwise a stolen
                // drag would snap under the finger.
                if (settleCtl.job === me) {
                    offsetX = 0f
                    settling = false
                }
            }
        }
        settleCtl.job = me
    }

    val density = LocalDensity.current
    val commitFloorPx = with(density) { commitFloor.toPx() }
    val flingPx = with(density) { flingSpeed.toPx() }
    val gapPx = with(density) { pageGap.toPx() }

    BoxWithConstraints(
        modifier
            .clipToBounds()
            .pointerInput(commitFloorPx, flingPx, gapPx, commitFraction) {
                // Drag totals live here, not in composition state: the
                // release decision must read the live total, while the
                // layout only needs the mirrored offsetX.
                var rawX = 0f
                var totalX = 0f
                var tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        if (settlingRef.value) {
                            // Take over mid-settle: the commit handoff never
                            // ran, so the center still shows the old page -
                            // continue from the live offset, no jump, no dead
                            // window. Clearing the holder first keeps the
                            // cancelled settle's finally from parking us.
                            val old = settleCtl.job
                            settleCtl.job = null
                            old?.cancel()
                            settling = false
                            rawX = offsetX
                            totalX = offsetX
                            tracker = VelocityTracker()
                        } else {
                            rawX = 0f
                            totalX = 0f
                            tracker = VelocityTracker()
                        }
                    },
                    onDragCancel = {
                        totalX = 0f
                        settle(CoverSkipDecision.None, size.width.toFloat() + gapPx)
                    },
                    onDragEnd = {
                        val pagePx = size.width.toFloat()
                        val vx = try {
                            tracker.calculateVelocity().x
                        } catch (_: Exception) {
                            0f
                        }
                        val decision = if (!settlingRef.value) {
                            coverSkipCommit(
                                dx = totalX,
                                vx = vx,
                                width = pagePx,
                                floor = commitFloorPx,
                                fling = flingPx,
                                canNext = latestCanNext,
                                canPrev = latestCanPrev,
                                lockedHorizontal = true,
                                fraction = commitFraction,
                            )
                        } else {
                            CoverSkipDecision.None
                        }
                        settle(decision, pagePx + gapPx)
                    },
                    onHorizontalDrag = { change, dx ->
                        if (!settlingRef.value) {
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val pagePx = size.width.toFloat()
                            // Absolute from the gesture start, like the old
                            // cover pager: re-banding an already-banded total
                            // would compound the 0.35 and freeze blocked
                            // edges far too early.
                            rawX += dx
                            totalX = coverSkipRubberBand(
                                rawX,
                                pagePx,
                                latestCanNext,
                                latestCanPrev,
                            )
                            offsetX = totalX
                        }
                    },
                )
            },
    ) {
        val shiftPx = with(density) { maxWidth.toPx() + pageGap.toPx() }
        // Pure slide, no resize: every page holds its render through the
        // whole travel. Neighbours sit a page off each side and glide in
        // over ground, cut at the screen edge. A missing neighbour is empty
        // ground, never an error caption.
        if (previous != null) {
            Box(
                pageModifier
                    .align(Alignment.Center)
                    .graphicsLayer { translationX = offsetX - shiftPx },
            ) {
                pageContent(previous)
            }
        }
        Box(
            pageModifier
                .align(Alignment.Center)
                .graphicsLayer { translationX = offsetX },
        ) {
            pageContent(centerPage)
        }
        if (next != null) {
            Box(
                pageModifier
                    .align(Alignment.Center)
                    .graphicsLayer { translationX = offsetX + shiftPx },
            ) {
                pageContent(next)
            }
        }
    }
}
