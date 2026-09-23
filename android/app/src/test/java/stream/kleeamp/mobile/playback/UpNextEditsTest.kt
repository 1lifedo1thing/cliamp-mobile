package stream.kleeamp.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class UpNextEditsTest {
    @Test
    fun movingAnyItemKeepsTheSamePlayingOccurrence() {
        val upNext = (0..5).toList()
        for (current in upNext.indices) {
            for (from in upNext.indices) {
                checkAllTargets(upNext, current, from)
            }
        }
        assertEquals(-1, upNextIndexAfterMove(-1, 2, 0))
    }

    private fun checkAllTargets(upNext: List<Int>, current: Int, from: Int) {
        for (to in upNext.indices) {
            val moved = upNext.toMutableList().apply { add(to, removeAt(from)) }
            assertEquals(current, moved[upNextIndexAfterMove(current, from, to)])
        }
    }
}
