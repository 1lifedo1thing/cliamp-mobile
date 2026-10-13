package stream.kleeamp.mobile.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.player.vis.StereoMetrics
import stream.kleeamp.mobile.player.vis.Visualizer
import stream.kleeamp.mobile.player.vis.VisualizerMeter

/**
 * The meter pager: full-bleed pages on the shared [SkipPager] engine with
 * the meter's own rules - the order wraps so both directions always commit,
 * and the commit share keeps the old easy fifth of the width plus the fling
 * shortcut, so short flicks page too.
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
    SkipPager(
        current = current,
        previous = previous,
        next = next,
        currentId = current.id,
        canGoNext = true,
        canGoPrevious = true,
        onGoNext = onSelectNext,
        onGoPrevious = onSelectPrevious,
        modifier = modifier,
        commitFraction = 0.2f,
        commitFloor = 0.dp,
        pageContent = { mode ->
            VisualizerMeter(
                mode = mode,
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
        },
    )
}
