package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Test

class BarsDotCoreTest {

    @Test
    fun dotsFillBottomUp() {
        // 8 dot rows: level 0.5 lights the bottom 4 (dotY 0/8..3/8 < 0.5).
        assertEquals(false, BarsDotCore.dotLit(8, 0, 0.5f))
        assertEquals(false, BarsDotCore.dotLit(8, 3, 0.5f))
        assertEquals(true, BarsDotCore.dotLit(8, 4, 0.5f))
        assertEquals(true, BarsDotCore.dotLit(8, 7, 0.5f))
    }

    @Test
    fun silenceLightsNothingFullScaleLightsAll() {
        for (row in 0 until 8) {
            assertEquals(false, BarsDotCore.dotLit(8, row, 0f))
        }
    }
}

class BricksCoreTest {

    @Test
    fun litRowsScaleWithLevel() {
        assertEquals(0, BricksCore.litRows(0f, 10))
        assertEquals(10, BricksCore.litRows(1f, 10))
        assertEquals(5, BricksCore.litRows(0.55f, 10))
        assertEquals(0, BricksCore.litRows(-0.5f, 10))
    }
}

class BarsOutlineCoreTest {

    @Test
    fun peakRowHoldsTheLevel() {
        // 7 rows: level 0.5 lands in row 3, like the terminal original.
        assertEquals(3, BarsOutlineCore.peakRow(0.5f, 7))
        assertEquals(0, BarsOutlineCore.peakRow(0.99f, 7))
        assertEquals(6, BarsOutlineCore.peakRow(0.01f, 7))
    }

    @Test
    fun silenceAndFullScaleDrawNothing() {
        assertEquals(null, BarsOutlineCore.peakRow(0f, 7))
        assertEquals(null, BarsOutlineCore.peakRow(-0.5f, 7))
        assertEquals(null, BarsOutlineCore.peakRow(1f, 7))
        assertEquals(null, BarsOutlineCore.peakRow(0.5f, 0))
    }
}

class ColumnsCoreTest {

    @Test
    fun columnsInterpolateToCount() {
        val cols = ColumnsCore.columns(floatArrayOf(0f, 1f), 5)
        assertEquals(5, cols.size)
        assertEquals(0f, cols.first(), 1e-6f)
        assertEquals(1f, cols.last(), 1e-6f)
    }

    @Test
    fun topFracIsThePartialCap() {
        assertEquals(0.5f, ColumnsCore.topFrac(0.3125f, 8), 1e-6f)
        assertEquals(0f, ColumnsCore.topFrac(0.5f, 0), 1e-6f)
    }
}
