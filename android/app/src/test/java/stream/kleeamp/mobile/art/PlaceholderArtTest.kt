package stream.kleeamp.mobile.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderArtTest {

    @Test
    fun indexIsStableAndInRange() {
        val keys = listOf("lofi", "https://radio.example/stream", "", "mangoradio", "NCS Dubstep")
        for (key in keys) {
            val index = PlaceholderArt.indexFor(key)
            assertTrue("index $index out of range", index in 0 until PlaceholderArt.COUNT)
            assertEquals(index, PlaceholderArt.indexFor(key))
        }
    }

    @Test
    fun differentKeysSpreadAcrossTheSet() {
        val seen = (0 until 40).map { PlaceholderArt.indexFor("station-$it") }.toSet()
        assertTrue("only ${seen.size} designs used", seen.size > 5)
    }
}
