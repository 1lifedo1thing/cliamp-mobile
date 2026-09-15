package stream.cliamp.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueEditsTest {
    @Test
    fun movingAnyItemKeepsTheSamePlayingOccurrence() {
        val queue = (0..5).toList()
        for (current in queue.indices) {
            for (from in queue.indices) {
                for (to in queue.indices) {
                    val moved = queue.toMutableList().apply { add(to, removeAt(from)) }
                    assertEquals(current, moved[queueIndexAfterMove(current, from, to)])
                }
            }
        }
        assertEquals(-1, queueIndexAfterMove(-1, 2, 0))
    }
}
