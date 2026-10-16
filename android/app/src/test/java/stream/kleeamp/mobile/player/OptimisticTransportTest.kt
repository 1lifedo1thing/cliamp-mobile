package stream.kleeamp.mobile.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimisticTransportTest {

    @Test
    fun graceWindowCoversThreeSeconds() {
        assertTrue(isWithinGrace(3_999L, 1_000L))
        assertFalse(isWithinGrace(4_000L, 1_000L))
        assertFalse(isWithinGrace(1_000L, 0L))
        assertFalse(isWithinGrace(5_000L, -1L))
    }

    @Test
    fun playingStaysOnDuringGraceWhenIntended() {
        assertTrue(effectivePlaying(playing = false, wantPlaying = true, inGrace = true))
        assertTrue(effectivePlaying(playing = true, wantPlaying = true, inGrace = true))
    }

    @Test
    fun pauseInsideGraceStaysPaused() {
        assertFalse(effectivePlaying(playing = false, wantPlaying = false, inGrace = true))
    }

    @Test
    fun bufferingAndReconnectHiddenOnlyInGrace() {
        assertFalse(effectiveBuffering(buffering = true, inGrace = true))
        assertTrue(effectiveBuffering(buffering = true, inGrace = false))
        assertEquals(0, effectiveReconnect(2, inGrace = true))
        assertEquals(2, effectiveReconnect(2, inGrace = false))
    }
}
