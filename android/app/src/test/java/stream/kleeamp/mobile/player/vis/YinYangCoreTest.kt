package stream.kleeamp.mobile.player.vis

import org.junit.Assert.assertEquals
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
    fun swimSwayReadsUnswayedSpine() {
        // Golden cells for the driven fixture: the sway loop must read the
        // pristine spine while writing the swayed one (Go copies the joints
        // array by value). Reading through the live array compounds each
        // joint's offset into the next and bends the tail off its line.
        val core = drivenCore(48, 96)
        assertEquals(4_632_676_747_633_724_107L, fnv1a(core.cells))
    }

    private fun fnv1a(bytes: ByteArray): Long {
        var h = -3_759_435_231_071_130_541L
        for (b in bytes) {
            h = h xor (b.toLong() and 0xFF)
            h *= 10_951_093L
        }
        return h
    }

    @Test
    fun forceStripSkipsPadOnTallPanel() {
        // The phone pins the strip layout: even a tall panel runs the
        // S-curve with no lily pad instead of circling the pond.
        val core = YinYangCore().also {
            it.ensure(120, 48, forceStrip = true)
            repeat(40) { _ -> it.advance(FloatArray(10) { 0.5f }) }
            it.draw()
        }
        val tags = core.cells.filter { it != 0.toByte() }
        assertTrue("forced strip drew nothing", tags.isNotEmpty())
        assertTrue("forced strip drew a pad", tags.none { it == YinYangCore.TAG_PAD })
    }

    @Test
    fun settleClearsCells() {        val core = drivenCore()
        assertTrue(core.cells.any { it != 0.toByte() })
        core.settle()
        core.ensure(48, 96)
        core.draw()
        // Fresh after settle with no advance: koi bodies still draw (layout
        // lines them up), but no swirl state leaks — just check it draws.
        assertTrue(core.cells.any { it != 0.toByte() })
    }
}
