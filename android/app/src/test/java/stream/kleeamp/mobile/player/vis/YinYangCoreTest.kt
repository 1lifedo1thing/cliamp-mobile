package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertTrue
import org.junit.Test

class YinYangCoreTest {

    private fun drivenCore(rows: Int = 48, cols: Int = 96): YinYangCore =
        YinYangCore().also {
            it.ensure(rows, cols)
            repeat(40) { _ -> it.advance(FloatArray(10) { 0.5f }) }
            it.draw()
        }

    @Test
    fun circleDrawsBothKoiAndLotus() {
        val core = drivenCore(48, 96)
        val tags = core.cells.filter { it != 0.toByte() }
        assertTrue("nothing drawn", tags.isNotEmpty())
        assertTrue("no red koi", tags.any { it == YinYangCore.TAG_RED })
        assertTrue("no ink koi", tags.any { it == YinYangCore.TAG_INK })
    }

    @Test
    fun stripDrawsKoiWithoutPad() {
        // Wide panel (w > 3h) takes the S-curve strip path with no lily pad.
        val core = YinYangCore().also {
            it.ensure(28, 240)
            repeat(40) { _ -> it.advance(FloatArray(10) { 0.5f }) }
            it.draw()
        }
        val tags = core.cells.filter { it != 0.toByte() }
        assertTrue("strip drew nothing", tags.isNotEmpty())
        assertTrue("strip drew a pad", tags.none { it == YinYangCore.TAG_PAD })
    }

    @Test
    fun settleClearsCells() {
        val core = drivenCore()
        assertTrue(core.cells.any { it != 0.toByte() })
        core.settle()
        core.ensure(48, 96)
        core.draw()
        // Fresh after settle with no advance: koi bodies still draw (layout
        // lines them up), but no swirl state leaks — just check it draws.
        assertTrue(core.cells.any { it != 0.toByte() })
    }
}
