package stream.kleeamp.mobile.chrome

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadBlocksTest {
    @Test fun emptyStaysEmpty() {
        assertEquals(0, blocksFilled(0f, 8))
        assertEquals(0, blocksFilled(-1f, 8))
    }

    @Test fun fullFillsAll() {
        assertEquals(8, blocksFilled(1f, 8))
        assertEquals(8, blocksFilled(2f, 8))
    }

    @Test fun halfFillsHalf() {
        assertEquals(4, blocksFilled(0.5f, 8))
        assertEquals(5, blocksFilled(0.6f, 8))
    }

    @Test fun degenerateBlockCountFillsNone() {
        assertEquals(0, blocksFilled(0.9f, 0))
        assertEquals(0, blocksFilled(0.9f, -3))
    }
}
