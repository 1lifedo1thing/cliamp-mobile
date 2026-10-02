package stream.kleeamp.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationProbeTest {
    @Test fun nonHttpReadsZeroWithoutTouchingTheFramework() {
        assertEquals(0L, DurationProbe.probeMs("not a url"))
        assertEquals(0L, DurationProbe.probeMs("file:///nope.mp3"))
    }

    @Test fun unreachableHostReadsZero() {
        assertEquals(0L, DurationProbe.probeMs("https://example.invalid/x.mp3"))
    }
}
