package stream.kleeamp.mobile.player

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.theme.KleeampShape

/**
 * The Now Playing cover pager: plate-sized square pages centred in a
 * full-width viewport, gliding over ground and cut at the screen edge.
 * Runs on the shared [SkipPager] engine with the cover's own rules -
 * rubber-banded ends and the 0.28 commit share.
 */
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
    SkipPager(
        current = current,
        previous = previous,
        next = next,
        currentId = current?.id,
        canGoNext = canSkipNext,
        canGoPrevious = canSkipPrevious,
        onGoNext = onSkipNext,
        onGoPrevious = onSkipPrevious,
        modifier = modifier,
        // Pure slide, no resize: every page holds full scale through the
        // whole travel. The clip lives on the page layer itself: an
        // unclipped swipe layer composites square and leaks gray corners
        // mid-swipe.
        pageModifier = Modifier
            .fillMaxHeight()
            .aspectRatio(1f, matchHeightConstraintsFirst = true)
            .graphicsLayer {
                clip = true
                shape = RoundedCornerShape(KleeampShape.large)
            },
        pageContent = { station ->
            StationArt(
                station = station,
                modifier = Modifier.fillMaxSize(),
                glow = false,
                breathe = false,
            )
        },
    )
}
