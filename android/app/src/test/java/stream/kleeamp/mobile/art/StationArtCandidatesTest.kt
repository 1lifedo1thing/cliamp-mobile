package stream.kleeamp.mobile.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationArtCandidatesTest {

    @Test fun faviconBeatsHomepageScrape() {
        // Homepage is never a direct candidate: a usable favicon means no scrape.
        val out = StationArtSource.artCandidates(
            cover = "",
            favicon = "https://example.com/icon.png",
            homepage = "https://example.com/",
        )
        assertEquals(listOf("https://example.com/icon.png"), out)
    }

    @Test fun knownCoverWinsOverFavicon() {
        val out = StationArtSource.artCandidates(
            cover = "https://cdn.example.com/art.jpg",
            favicon = "https://example.com/favicon.ico",
            homepage = "https://example.com/",
        )
        assertEquals(
            listOf("https://cdn.example.com/art.jpg", "https://example.com/favicon.ico"),
            out,
        )
    }

    @Test fun duplicateCoverAndFaviconCollapse() {
        val url = "https://example.com/logo.png"
        val out = StationArtSource.artCandidates(url, url, "https://example.com/")
        assertEquals(listOf(url), out)
    }

    @Test fun nonHttpCandidatesIgnored() {
        val out = StationArtSource.artCandidates("", "", "https://example.com/")
        assertTrue(out.isEmpty())
    }

    @Test fun svgSkippedForNextCandidate() {
        val out = StationArtSource.artCandidates(
            cover = "https://example.com/logo.svg",
            favicon = "https://example.com/icon.png",
            homepage = "https://example.com/",
        )
        assertEquals(listOf("https://example.com/icon.png"), out)
    }

    @Test fun svgWithQuerySkipped() {
        assertTrue(StationArtSource.isSvgUrl("https://example.com/i.svg?v=2"))
        assertFalse(StationArtSource.isSvgUrl("https://example.com/icon.png"))
    }

    @Test fun missBackoffRestsStation() {
        val key = "test-backoff-${System.nanoTime()}"
        try {
            assertFalse(StationArtSource.isMissOut(key))
            StationArtSource.seedMissForTest(key)
            assertTrue(StationArtSource.isMissOut(key))
            StationArtSource.clearMissForTest(key)
            assertFalse(StationArtSource.isMissOut(key))
        } finally {
            StationArtSource.clearMissForTest(key)
        }
    }
}
