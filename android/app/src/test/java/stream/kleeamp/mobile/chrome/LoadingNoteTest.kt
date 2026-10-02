package stream.kleeamp.mobile.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingNoteTest {
    @Test fun framesCycleEvery100ms() {
        assertEquals("⠋", spinnerFrame(0))
        assertEquals("⠋", spinnerFrame(99))
        assertEquals("⠙", spinnerFrame(100))
        assertEquals("⠏", spinnerFrame(900))
        assertEquals("⠋", spinnerFrame(1000))
    }

    @Test fun allTenFramesAppearInOneCycle() {
        val seen = (0L until 1000L step 100).map { spinnerFrame(it) }.toSet()
        assertEquals(10, seen.size)
        assertTrue(seen.all { it in SpinnerFrames })
    }
}
