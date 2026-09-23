package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButterflyCoreTest {

    @Test
    fun silenceDrawsNothing() {
        val grid = ButterflyCore.grid(FloatArray(64), 16, 60, 10)
        assertFalse(grid.any { it })
    }

    @Test
    fun fullEnergyLightsTheWingsAndSpine() {
        val rows = 16
        val cols = 60
        val grid = ButterflyCore.grid(FloatArray(64) { 1f }, rows, cols, 10)
        val center = cols / 2
        for (row in 0 until rows) {
            assertTrue("spine missing at $row", grid[row * cols + center])
        }
        assertTrue(grid.any { it })
    }

    @Test
    fun bothWingsAreDrawnAboutTheCentre() {
        val rows = 16
        val cols = 61
        val grid = ButterflyCore.grid(FloatArray(64) { 0.8f }, rows, cols, 25)
        val center = cols / 2
        var left = 0
        var right = 0
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                if (!grid[row * cols + col]) continue
                if (col < center) left++ else right++
            }
        }
        assertTrue("left wing missing", left > 0)
        assertTrue("right wing missing", right > 0)
    }

    @Test
    fun deterministicForTheSameFrame() {
        val a = ButterflyCore.grid(FloatArray(64) { 0.5f }, 12, 40, 7)
        val b = ButterflyCore.grid(FloatArray(64) { 0.5f }, 12, 40, 7)
        assertEquals(a.toList(), b.toList())
    }
}
