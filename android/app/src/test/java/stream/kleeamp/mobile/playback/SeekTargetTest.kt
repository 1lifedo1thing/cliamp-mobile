package stream.kleeamp.mobile.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeekTargetTest {

    @Test
    fun midFractionScalesWithDuration() {
        assertEquals(60_000L, seekTargetMs(240_000L, 0.25f))
        assertEquals(120_000L, seekTargetMs(240_000L, 0.5f))
    }

    @Test
    fun endSeekClampsShortOfTailInsteadOfDropping() {
        // A fling to the edge must land and play out, never snap back.
        assertEquals(239_750L, seekTargetMs(240_000L, 1f))
        assertEquals(239_750L, seekTargetMs(240_000L, 2f))
        assertEquals(239_750L, seekTargetMs(240_000L, 0.9999f))
    }

    @Test
    fun fractionClampsBothWays() {
        assertEquals(0L, seekTargetMs(240_000L, 0f))
        assertEquals(0L, seekTargetMs(240_000L, -1f))
    }

    @Test
    fun noSaneTargetWithoutDuration() {
        assertNull(seekTargetMs(0L, 0.5f))
        assertNull(seekTargetMs(-5L, 0.5f))
        assertNull(seekTargetMs(C.TIME_UNSET, 0.5f))
    }

    @Test
    fun tinyDurationNeverGoesNegative() {
        assertEquals(0L, seekTargetMs(100L, 1f))
        assertEquals(0L, seekTargetMs(100L, 0.5f))
    }
}
