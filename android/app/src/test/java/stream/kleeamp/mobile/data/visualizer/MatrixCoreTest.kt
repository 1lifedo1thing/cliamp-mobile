package stream.kleeamp.mobile.data.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixCoreTest {

    @Test
    fun cellIsDeterministic() {
        val a = MatrixCore.cell(0.8f, 3, 2, 8, 12L)
        val b = MatrixCore.cell(0.8f, 3, 2, 8, 12L)
        assertEquals(a, b)
    }

    @Test
    fun charactersComeFromTheGlyphSet() {
        var seen = 0
        for (col in 0 until 32) {
            for (row in 0 until 8) {
                val cell = MatrixCore.cell(1f, col, row, 8, 5L) ?: continue
                seen++
                assertTrue(cell.char.isDefined())
                assertTrue(cell.tier in 0..2)
            }
        }
        assertTrue("no cells drawn at full energy", seen > 0)
    }

    @Test
    fun rainAppearsOverFrames() {
        var lit = 0
        for (frame in 0L..60L step 4L) {
            for (col in 0 until 8) {
                for (row in 0 until 8) {
                    if (MatrixCore.cell(1f, col, row, 8, frame) != null) lit++
                }
            }
        }
        assertTrue("no rain over 16 frames", lit > 0)
    }

    @Test
    fun gateClosesWhenTheColumnIsStill() {
        var lit = 0
        for (col in 0 until 64) {
            if (MatrixCore.cell(0f, col, 0, 8, 100L) != null) lit++
        }
        assertTrue(lit < 64)
    }
}
