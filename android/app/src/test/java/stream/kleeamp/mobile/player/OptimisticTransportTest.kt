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

    @Test
    fun wantCoversNavBeforeItemsArrive() {
        // Right after a tap the queue is building and Media3 holds nothing
        // yet: intent still reads as wanted so the grace shows playing.
        assertTrue(shouldWantPlaying(playWhenReady = true, mediaItemCount = 0, transitioning = true))
        assertTrue(shouldWantPlaying(playWhenReady = true, mediaItemCount = 1, transitioning = false))
        // Paused stays unwanted even mid-shuffle; empty idle wants nothing.
        assertFalse(shouldWantPlaying(playWhenReady = false, mediaItemCount = 1, transitioning = true))
        assertFalse(shouldWantPlaying(playWhenReady = true, mediaItemCount = 0, transitioning = false))
        assertFalse(shouldWantPlaying(playWhenReady = false, mediaItemCount = 0, transitioning = false))
    }

    @Test
    fun idleWithIntentAndNavCountAsLoading() {
        // IDLE holding items with intent is a load, not a pause.
        assertTrue(shouldReportBuffering(false, true, 1, true, false, false))
        // A nav in flight without audio yet is a load too.
        assertTrue(shouldReportBuffering(false, false, 1, true, true, false))
        assertTrue(shouldReportBuffering(true, false, 1, true, false, false))
        // Paused, empty idle and actively playing never report loading.
        assertFalse(shouldReportBuffering(false, true, 1, false, false, false))
        assertFalse(shouldReportBuffering(false, true, 0, true, false, false))
        assertFalse(shouldReportBuffering(false, false, 0, false, false, false))
        assertFalse(shouldReportBuffering(false, false, 1, true, true, true))
        assertFalse(shouldReportBuffering(false, false, 1, true, false, true))
    }
}
