package stream.kleeamp.mobile.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverSkipTest {
    private val width = 1000f
    private val floor = 200f
    private val fling = 900f

    private fun commit(
        dx: Float,
        vx: Float = 0f,
        width: Float = this.width,
        floor: Float = this.floor,
        fling: Float = this.fling,
        canNext: Boolean = true,
        canPrev: Boolean = true,
        lockedHorizontal: Boolean = true,
    ) = coverSkipCommit(dx, vx, width, floor, fling, canNext, canPrev, lockedHorizontal)

    @Test fun shortDragSpringsBack() {
        assertEquals(CoverSkipDecision.None, commit(dx = -100f))
        assertEquals(CoverSkipDecision.None, commit(dx = 100f))
        assertEquals(CoverSkipDecision.None, commit(dx = 0f))
    }

    @Test fun farDragCommitsOnceNeighbourVisible() {
        // threshold = max(200, 0.28 * 1000) = 280
        assertEquals(CoverSkipDecision.None, commit(dx = -279f))
        assertEquals(CoverSkipDecision.Next, commit(dx = -280f))
        assertEquals(CoverSkipDecision.Next, commit(dx = -500f))
        assertEquals(CoverSkipDecision.None, commit(dx = 279f))
        assertEquals(CoverSkipDecision.Previous, commit(dx = 280f))
        assertEquals(CoverSkipDecision.Previous, commit(dx = 500f))
    }

    @Test fun flingCommitsEvenWhenShort() {
        assertEquals(CoverSkipDecision.Next, commit(dx = -50f, vx = -1000f))
        assertEquals(CoverSkipDecision.Previous, commit(dx = 50f, vx = 1000f))
    }

    @Test fun slowFlingBelowThresholdCancels() {
        assertEquals(CoverSkipDecision.None, commit(dx = -50f, vx = -500f))
        assertEquals(CoverSkipDecision.None, commit(dx = 50f, vx = 500f))
    }

    @Test fun wrongWayVelocityDoesNotCommit() {
        // Dragging left but moving right on release is a cancel, not a skip.
        assertEquals(CoverSkipDecision.None, commit(dx = -50f, vx = 1000f))
        assertEquals(CoverSkipDecision.None, commit(dx = 50f, vx = -1000f))
    }

    @Test fun blockedDirectionNeverCommits() {
        assertEquals(CoverSkipDecision.None, commit(dx = -500f, vx = 0f, canNext = false))
        assertEquals(CoverSkipDecision.None, commit(dx = -50f, vx = -2000f, canNext = false))
        assertEquals(CoverSkipDecision.None, commit(dx = 500f, vx = 0f, canPrev = false))
        assertEquals(CoverSkipDecision.None, commit(dx = 50f, vx = 2000f, canPrev = false))
    }

    @Test fun verticalLockNeverCommits() {
        assertEquals(
            CoverSkipDecision.None,
            commit(dx = -500f, vx = -2000f, lockedHorizontal = false),
        )
        assertEquals(
            CoverSkipDecision.None,
            commit(dx = 500f, vx = 2000f, lockedHorizontal = false),
        )
    }

    @Test fun zeroWidthNeverCommits() {
        assertEquals(CoverSkipDecision.None, commit(dx = -500f, vx = -2000f, width = 0f))
    }

    @Test fun floorWinsOnNarrowCovers() {
        // width 500 -> 0.28 * width = 140 < floor 200, so floor rules.
        assertEquals(CoverSkipDecision.None, commit(dx = -199f, width = 500f))
        assertEquals(CoverSkipDecision.Next, commit(dx = -200f, width = 500f))
    }

    @Test fun fractionWinsOnWideCovers() {
        // width 2000 -> 0.28 * width = 560 > floor 200, so fraction rules.
        assertEquals(CoverSkipDecision.None, commit(dx = -559f, width = 2000f))
        assertEquals(CoverSkipDecision.Next, commit(dx = -560f, width = 2000f))
    }

    @Test fun rubberBandStaysWithinEdge() {
        val edge = 0.18f * width
        val blockedNext = coverSkipRubberBand(-width, width, canNext = false, canPrev = true)
        val blockedPrev = coverSkipRubberBand(width, width, canNext = true, canPrev = false)
        assertTrue(kotlin.math.abs(blockedNext) <= edge + 0.001f)
        assertTrue(kotlin.math.abs(blockedPrev) <= edge + 0.001f)
        assertEquals(-width, coverSkipRubberBand(-width, width, canNext = true, canPrev = true))
        assertEquals(width, coverSkipRubberBand(width, width, canNext = true, canPrev = true))
    }
}
