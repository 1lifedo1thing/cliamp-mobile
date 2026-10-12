package stream.kleeamp.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

class PlaylistFilesTest {

    private fun localSong(path: String, title: String = path.substringAfterLast('/')) = Station(
        id = "local:$path",
        name = title,
        url = "file://$path",
        source = StationSource.Local,
    )

    @Test
    fun m3uParsesExtinfAndResolvesRelative() {
        val entries = parseM3u(
            "#EXTM3U\n#EXTINF:123,Singer - Song\ntrack.mp3\nhttps://x.test/s.mp3\n",
            baseDir = "/music",
        )
        assertEquals(2, entries.size)
        assertEquals(PlaylistEntry("/music/track.mp3", "Singer - Song", 123), entries[0])
        assertEquals(PlaylistEntry("https://x.test/s.mp3", "", 0), entries[1])
    }

    @Test
    fun m3uSkipsCommentsAndBlanks() {
        val entries = parseM3u("#EXTM3U\n# a comment\n\n/a.mp3\n")
        assertEquals(listOf(PlaylistEntry("/a.mp3")), entries)
    }

    @Test
    fun plsParsesTriples() {
        val entries = parsePls(
            "[playlist]\nFile1=/a.mp3\nTitle1=Song\nLength1=200\n" +
                "File2=https://x.test/s.mp3\nNumberOfEntries=2\nVersion=2\n",
        )
        assertEquals(2, entries.size)
        assertEquals(PlaylistEntry("/a.mp3", "Song", 200), entries[0])
        assertEquals(PlaylistEntry("https://x.test/s.mp3", "", 0), entries[1])
    }

    @Test
    fun detectSniffsContent() {
        assertEquals(PlaylistKind.M3U, detectPlaylistKind("list.txt", "#EXTM3U\n/a.mp3\n"))
        assertEquals(PlaylistKind.PLS, detectPlaylistKind("list.txt", "[playlist]\nFile1=/a.mp3\n"))
        assertEquals(PlaylistKind.M3U, detectPlaylistKind("a.M3U8", ""))
        assertNull(detectPlaylistKind("a.txt", "hello"))
    }

    @Test
    fun m3uRoundTrips() {
        val stations = listOf(
            localSong("/music/a.mp3", "A").copy(artist = "Art", durationMs = 200_000),
            urlStation("https://x.test/s.mp3", ""),
        )
        val again = parseM3u(renderM3u(stations))
        assertEquals(2, again.size)
        assertEquals("/music/a.mp3", again[0].location)
        assertEquals("Art - A", again[0].title)
        assertEquals(200L, again[0].durationSec)
        assertEquals("https://x.test/s.mp3", again[1].location)
    }

    @Test
    fun matchPrefersPathThenUniqueName() {
        val lib = listOf(localSong("/music/a.mp3"), localSong("/other/b.mp3"))
        val match = matchEntries(
            listOf(
                PlaylistEntry("/music/a.mp3"),
                PlaylistEntry("b.mp3"),
                PlaylistEntry("/gone.mp3"),
                PlaylistEntry("https://x.test/s.mp3", "Stream"),
            ),
            lib,
        )
        assertEquals(3, match.matched.size)
        assertEquals(1, match.missing)
        // URL entries keep their EXTINF title and stay playable.
        assertEquals("Stream", match.matched[2].name)
        assertEquals("https://x.test/s.mp3", match.matched[2].url)
    }

    @Test
    fun ambiguousNamesStayMissing() {
        val lib = listOf(localSong("/a/same.mp3"), localSong("/b/same.mp3"))
        val match = matchEntries(listOf(PlaylistEntry("same.mp3")), lib)
        assertEquals(0, match.matched.size)
        assertEquals(1, match.missing)
    }

    @Test
    fun matchFindsPlainFilenameAgainstEncodedLibraryUrl() {
        // Library urls arrive percent-encoded from Uri.fromFile, while
        // playlist files carry plain filenames — the user's exact case.
        val lib = listOf(
            localSong("/storage/emulated/0/Music/21 Rooz Bad Mehdi Yarrahi.mp3").copy(
                url = "file:///storage/emulated/0/Music/21%20Rooz%20Bad%20Mehdi%20Yarrahi.mp3",
            ),
        )
        val match = matchEntries(listOf(PlaylistEntry("21 Rooz Bad Mehdi Yarrahi.mp3")), lib)
        assertEquals(1, match.matched.size)
        assertEquals(0, match.missing)
    }

    @Test
    fun matchFindsEncodedEntryPathAgainstPlainLibrary() {
        val lib = listOf(localSong("/music/a b.mp3"))
        val match = matchEntries(listOf(PlaylistEntry("/music/a%20b.mp3")), lib)
        assertEquals(1, match.matched.size)
        assertEquals(0, match.missing)
    }

    @Test
    fun dedupeKeepsFirst() {
        val (ids, dropped) = dedupeIds(listOf("a", "b", "a", "c", "b"))
        assertEquals(listOf("a", "b", "c"), ids)
        assertEquals(2, dropped)
    }

    @Test
    fun decodePathHandlesPercentUtf8() {
        assertEquals("/m/a b.mp3", decodePath("/m/a%20b.mp3"))
        assertEquals("/m/plus+kept.mp3", decodePath("/m/plus+kept.mp3"))
        assertEquals("/m/plain.mp3", decodePath("/m/plain.mp3"))
    }
}
