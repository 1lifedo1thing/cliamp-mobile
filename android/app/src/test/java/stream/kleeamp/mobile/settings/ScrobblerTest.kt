package stream.kleeamp.mobile.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrobblerTest {

    @Test
    fun streamTitleSplitsArtist() {
        assertEquals("Singer" to "Song", splitStreamTitle("Singer - Song"))
        assertEquals("" to "Just words", splitStreamTitle("Just words"))
        assertEquals("A" to "B - C", splitStreamTitle("A - B - C"))
    }

    @Test
    fun retryBacksOffThenCapsAtDaily() {
        assertEquals(60_000L, retryDelayMs(0))
        assertEquals(300_000L, retryDelayMs(1))
        assertEquals(900_000L, retryDelayMs(2))
        assertEquals(3_600_000L, retryDelayMs(3))
        assertEquals(21_600_000L, retryDelayMs(4))
        assertEquals(86_400_000L, retryDelayMs(5))
        assertEquals(86_400_000L, retryDelayMs(99))
        assertEquals(60_000L, retryDelayMs(-1))
        var prev = 0L
        for (i in 0..6) {
            val d = retryDelayMs(i)
            assertTrue(d >= prev)
            prev = d
        }
    }
}
