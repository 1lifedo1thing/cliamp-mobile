package stream.kleeamp.mobile.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.player.vis.StereoMetrics
import stream.kleeamp.mobile.player.vis.Visualizer
import stream.kleeamp.mobile.player.vis.VisualizerMeter

/** Holder for the active settle job, so a new drag can take over mid-settle. */
private class SettleCtl {
    var job: Job? = null
}

/**
 * The meter pages like the cover: the drag follows the finger, the previous
 * and next visualizers glide in from the sides, and releasing under the
 * threshold (or a cancelled gesture) slides back instead of paging.
 *
 * Touch stays on the well-behaved [detectHorizontalDragGestures] detector -
 * the same one the old fire-on-release swipe used - so taps, double-tap
 * fullscreen, the landscape pane scroll and the sheet dismiss all keep
 * working. Only the commit math ([coverSkipCommit]) is shared with the cover.
 */
@Composable
internal fun VisSkip(
    current: Visualizer,
    previous: Visualizer,
    next: Visualizer,
    onSelectPrevious: () -> Unit,
    onSelectNext: () -> Unit,
    columns: Int,
    live: Boolean,
    spectrumProvider: () -> FloatArray?,
    stereoProvider: () -> StereoMetrics?,
    brick: Dp,
    gap: Dp,
    generation: Int,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val animation = remember { Animatable(0f) }
    val latestOnPrev by rememberUpdatedState(onSelectPrevious)
    val latestOnNext by rememberUpdatedState(onSelectNext)
    val latestPrevious by rememberUpdatedState(previous)
    val latestNext by rememberUpdatedState(next)
    val latestCurrentId by rememberUpdatedState(current.id)
    // Read without restarting the detector: keying pointerInput on settling
    // restarts gesture tracking mid-touch, which drops the active drag - its
    // moves then go unconsumed, the sheet eats the leaked vertical noise and
    // dips, and the touch reads as dead. These stay live across restarts.
    val settlingRef = rememberUpdatedState(settling)
    val settleCtl = remember { SettleCtl() }

    // Commit handoff: once the page-off lands the incoming visualizer
    // centered, the center takes it over in the same frame as the snap, so
    // the snap is invisible. Cleared once the prefs write flows back as the
    // new mode (or lands anywhere else, e.g. a scope chip mid-settle).
    var swapTo by remember { mutableStateOf<Visualizer?>(null) }
    var swapFromId by remember { mutableStateOf<String?>(null) }
    val centerMode = swapTo ?: current

    // A new mode always starts centered: after a commit we snap to 0, and an
    // external select lands here with the offset already at 0.
    val currentId = current.id
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

    // Releases resolve fast, like the old fixed slide - no bouncy springs:
    // a commit slides the page off in 180ms, a cancel slides back in
    // 150ms. Long springy settles are what made the swipe feel heavy.
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
                // A takeover or a newer settle owns the meter now: only the
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
    val flingPx = with(density) { 900.dp.toPx() }
    val gapPx = with(density) { CoverSkipPageGap.toPx() }

    BoxWithConstraints(
        modifier
            .clipToBounds()
            .pointerInput(flingPx, gapPx) {
                // Drag totals live here, not in composition state: the
                // release decision must read the live total, while the
                // layout only needs the mirrored offsetX. settling is read
                // through the ref above so this detector never restarts
                // mid-touch (see the note at settlingRef).
                var totalX = 0f
                var tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        if (settlingRef.value) {
                            // Take over mid-settle: the commit handoff never
                            // ran, so the center still shows the old mode -
                            // continue from the live offset, no jump, no dead
                            // window. Clearing the holder first keeps the
                            // cancelled settle's finally from parking us.
                            val old = settleCtl.job
                            settleCtl.job = null
                            old?.cancel()
                            settling = false
                            totalX = offsetX
                            tracker = VelocityTracker()
                        } else {
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
                                // The meter keeps its old easy rule: a fifth
                                // of the width, no minimum - plus the fling
                                // shortcut, so short flicks page too.
                                floor = 0f,
                                fling = flingPx,
                                // The order wraps: both directions are
                                // always free, never rubber-banded.
                                canNext = true,
                                canPrev = true,
                                lockedHorizontal = true,
                                fraction = 0.2f,
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
                            totalX = (totalX + dx).coerceIn(-pagePx, pagePx)
                            offsetX = totalX
                        }
                    },
                )
            },
    ) {
        val shiftPx = with(density) { maxWidth.toPx() + CoverSkipPageGap.toPx() }
        // Pure slide, no resize: every page holds its render through the
        // whole travel. Neighbours sit a page off each side and glide in
        // over ground, cut at the screen edge.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX - shiftPx },
        ) {
            VisualizerMeter(
                mode = previous,
                columns = columns,
                live = live,
                brick = brick,
                gap = gap,
                modifier = Modifier
                    .fillMaxSize()
                    .doubleTapToFullscreen(onDoubleTap),
                spectrumProvider = spectrumProvider,
                stereoProvider = stereoProvider,
                generation = generation,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX },
        ) {
            VisualizerMeter(
                mode = centerMode,
                columns = columns,
                live = live,
                brick = brick,
                gap = gap,
                modifier = Modifier
                    .fillMaxSize()
                    .doubleTapToFullscreen(onDoubleTap),
                spectrumProvider = spectrumProvider,
                stereoProvider = stereoProvider,
                generation = generation,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX + shiftPx },
        ) {
            VisualizerMeter(
                mode = next,
                columns = columns,
                live = live,
                brick = brick,
                gap = gap,
                modifier = Modifier
                    .fillMaxSize()
                    .doubleTapToFullscreen(onDoubleTap),
                spectrumProvider = spectrumProvider,
                stereoProvider = stereoProvider,
                generation = generation,
            )
        }
    }
}
