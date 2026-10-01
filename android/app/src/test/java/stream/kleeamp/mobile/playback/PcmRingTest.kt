package stream.kleeamp.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRingTest {

    @Test
    fun latestWindowReadsBackInOrder() {
        PcmRing.clear()
        PcmRing.onFormat(44_100, 2)
        val src = FloatArray(100) { it / 100f }
        PcmRing.writeMono(src, src.size)
        val dst = FloatArray(100)
        PcmRing.readLatest(dst, 100)
        for (i in src.indices) assertEquals(src[i], dst[i], 1e-6f)
    }

    @Test
    fun shortHistoryZeroPads() {
        PcmRing.clear()
        PcmRing.writeMono(floatArrayOf(0.5f, 0.25f), 2)
        val dst = FloatArray(8)
        PcmRing.readLatest(dst, 8)
        for (i in 0 until 6) assertEquals(0f, dst[i], 0f)
        assertEquals(0.5f, dst[6], 1e-6f)
        assertEquals(0.25f, dst[7], 1e-6f)
    }

    @Test
    fun wrapsWithoutLosingOrder() {
        PcmRing.clear()
        val big = FloatArray(PcmRing.CAPACITY + 100) { (it % 1000) / 1000f }
        var off = 0
        while (off < big.size) {
            val chunk = minOf(2048, big.size - off)
            val tmp = big.copyOfRange(off, off + chunk)
            PcmRing.writeMono(tmp, chunk)
            off += chunk
        }
        val dst = FloatArray(100)
        PcmRing.readLatest(dst, 100)
        val tail = big.takeLast(100).toFloatArray()
        for (i in dst.indices) assertEquals(tail[i], dst[i], 1e-6f)
    }

    @Test
    fun windowReadsBackEndingAtCursor() {
        PcmRing.clear()
        val src = FloatArray(200) { it / 200f }
        PcmRing.writeMono(src, src.size)
        val dst = FloatArray(100)
        // Window ending at 200 == latest 100.
        PcmRing.readWindow(200L, dst, 100)
        val tail = src.takeLast(100).toFloatArray()
        for (i in dst.indices) assertEquals(tail[i], dst[i], 1e-6f)
        // Window ending at 100 == first 100.
        PcmRing.readWindow(100L, dst, 100)
        for (i in dst.indices) assertEquals(src[i], dst[i], 1e-6f)
    }

    @Test
    fun windowZeroPadsBeforeStreamStartAndClampsPastWritten() {
        PcmRing.clear()
        PcmRing.writeMono(floatArrayOf(0.5f, 0.25f), 2)
        val dst = FloatArray(4)
        PcmRing.readWindow(1L, dst, 4)
        assertEquals(0f, dst[0], 0f)
        assertEquals(0f, dst[1], 0f)
        assertEquals(0f, dst[2], 0f)
        assertEquals(0.5f, dst[3], 1e-6f)
        // End past written clamps: same as ending at written.
        PcmRing.readWindow(10_000L, dst, 4)
        assertEquals(0f, dst[0], 0f)
        assertEquals(0f, dst[1], 0f)
        assertEquals(0.5f, dst[2], 1e-6f)
        assertEquals(0.25f, dst[3], 1e-6f)
    }

    @Test
    fun clearResetsTheClock() {
        PcmRing.clear()
        assertEquals(0L, PcmRing.written())
        PcmRing.writeMono(floatArrayOf(1f), 1)
        assertTrue(PcmRing.written() > 0)
        PcmRing.clear()
        assertEquals(0L, PcmRing.written())
    }
}
