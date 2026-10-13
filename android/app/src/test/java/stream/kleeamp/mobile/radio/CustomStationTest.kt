package stream.kleeamp.mobile.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import stream.kleeamp.mobile.model.StationSource

class CustomStationTest {

    @Test
    fun blankUrlHasNoStation() {
        assertNull(customStation("name", ""))
        assertNull(customStation("name", "   "))
    }

    @Test
    fun bareHostGainsHttps() {
        val s = customStation("", "example.com/stream")!!
        assertEquals("https://example.com/stream", s.url)
        assertEquals(StationSource.Custom, s.source)
    }

    @Test
    fun httpSurvivesAndTrims() {
        val s = customStation("  jazz  ", "  http://example.com/live  ")!!
        assertEquals("jazz", s.name)
        assertEquals("http://example.com/live", s.url)
    }

    @Test
    fun blankNameFallsBackToHost() {
        assertEquals("example.com", customStation("", "https://example.com/live")!!.name)
        assertEquals("www.example.com".removePrefix("www."), customStation(" ", "https://www.example.com/x")!!.name)
    }

    @Test
    fun nonHttpSchemeRejected() {
        assertNull(customStation("ftp", "ftp://example.com/x"))
        assertNull(customStation("noproto", "just some words"))
    }
}
