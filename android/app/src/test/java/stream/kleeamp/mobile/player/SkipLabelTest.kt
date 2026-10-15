package stream.kleeamp.mobile.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SkipLabelTest {
    @Test
    fun backNamesSecondsWithMinus() {
        assertEquals("-15s", skipLabel(-15))
        assertEquals("-5s", skipLabel(-5))
    }

    @Test
    fun forwardNamesSecondsWithPlus() {
        assertEquals("+30s", skipLabel(30))
        assertEquals("+60s", skipLabel(60))
    }

    @Test
    fun zeroReadsExplicit() {
        assertEquals("+0s", skipLabel(0))
    }
}
