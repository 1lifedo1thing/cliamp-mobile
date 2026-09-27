package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
import org.junit.Test

class BarsDotCoreTest {

    @Test
    fun fillScalesWithLevel() {
        assertEquals(0, BarsDotCore.filled(36, 0f))
        assertEquals(36, BarsDotCore.filled(36, 1f))
        assertEquals(18, BarsDotCore.filled(36, 0.5f))
    }

    @Test
    fun fillClamps() {
        assertEquals(0, BarsDotCore.filled(36, -1f))
        assertEquals(36, BarsDotCore.filled(36, 2f))
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
    fun topsSpanTheWidth() {
        val pts = BarsOutlineCore.tops(floatArrayOf(0f, 0.5f, 1f), 90f, 100f)
        assertEquals(3, pts.size)
        assertEquals(100f, pts[0].y, 1e-6f)
        assertEquals(50f, pts[1].y, 1e-6f)
        assertEquals(0f, pts[2].y, 1e-6f)
        assertEquals(15f, pts[0].x, 1e-6f)
    }

    @Test
    fun emptyOrFlatCanvasGivesNoPoints() {
        assertEquals(0, BarsOutlineCore.tops(floatArrayOf(), 90f, 100f).size)
        assertEquals(0, BarsOutlineCore.tops(floatArrayOf(0.5f), 0f, 100f).size)
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
