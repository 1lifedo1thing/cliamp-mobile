package stream.kleeamp.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.prefs.PlaylistSort
import stream.kleeamp.mobile.prefs.sortedStations

class LocalGroupingTest {

    private fun song(
        name: String,
        artist: String = "artist",
        album: String = "album",
        albumArtist: String = "",
        track: Int = 0,
        disc: Int = 0,
    ) = Station(
        id = "local:$name",
        name = name,
        url = "file:///x/$name",
        source = StationSource.Local,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        trackNo = track,
        discNo = disc,
    )

    @Test fun decodePackedTrackSplitsDisc() {
        assertEquals(1 to 3, decodeDiscTrack(1003, 0))
    }

    @Test fun decodeRealDiscWins() {
        assertEquals(2 to 5, decodeDiscTrack(5, 2))
    }

    @Test fun decodeMissingStaysZero() {
        assertEquals(0 to 0, decodeDiscTrack(0, 0))
    }

    @Test fun albumSortUsesDiscThenTrack() {
        val songs = listOf(
            song("b-title", track = 2, disc = 1),
            song("a-title", track = 10, disc = 1),
            song("disc2-track1", track = 1, disc = 2),
        )
        val ordered = sortedStations(songs, PlaylistSort.Album).map { it.name }
        assertEquals(listOf("b-title", "a-title", "disc2-track1"), ordered)
    }

    @Test fun compilationStaysOneAlbumViaAlbumArtist() {
        val songs = listOf(
            song("t1", artist = "Daft Punk", album = "Alive 2007", albumArtist = "Daft Punk", track = 2),
            song("t2", artist = "Daft Punk", album = "Alive 2007", albumArtist = "Daft Punk", track = 1),
            song("t3", artist = "Gabrielle", album = "Alive 2007", albumArtist = "Daft Punk", track = 3),
        )
        val groups = albumsOf(songs)
        assertEquals(1, groups.size)
        assertEquals("Daft Punk", groups.single().displayArtist)
        assertEquals(listOf("t2", "t1", "t3"), groups.single().songs.map { it.name })
    }

    @Test fun sameNameSplitsByAlbumArtist() {
        val songs = listOf(
            song("a1", artist = "x", album = "Hits", albumArtist = "A"),
            song("b1", artist = "y", album = "Hits", albumArtist = "B"),
        )
        assertEquals(2, albumsOf(songs).size)
    }

    @Test fun untaggedFallsBackToAlbumName() {
        val songs = listOf(
            song("t1", artist = "a1", album = "Mix"),
            song("t2", artist = "a2", album = "Mix"),
        )
        val groups = albumsOf(songs)
        assertEquals(1, groups.size)
        assertEquals("Various Artists", groups.single().displayArtist)
    }

    @Test fun untaggedLoneArtistShowsThatArtist() {
        val songs = listOf(song("t1", artist = "Rush", album = "2112"))
        assertEquals("Rush", albumsOf(songs).single().displayArtist)
    }

    @Test fun artistsGroupAlphabetically() {
        val songs = listOf(song("t1", artist = "zebra"), song("t2", artist = "apple"))
        assertEquals(listOf("apple", "zebra"), artistsOf(songs).map { it.name })
    }

    @Test fun artistSongLivesUnderTrackArtistOnly() {
        val songs = listOf(
            song("t1", artist = "Daft Punk", album = "Alive 2007", albumArtist = "Daft Punk"),
            song("t2", artist = "Gabrielle", album = "Alive 2007", albumArtist = "Daft Punk"),
        )
        val groups = artistsOf(songs).associateBy({ it.name }, { it.songs.map { s -> s.name } })
        assertEquals(listOf("t1"), groups["Daft Punk"])
        assertEquals(listOf("t2"), groups["Gabrielle"])
        assertEquals(2, groups.values.sumOf { it.size })
    }

    @Test fun albumArtistOnlyGetsOwnRow() {
        val songs = listOf(
            song("t1", artist = "", album = "Mix", albumArtist = "DJ Mix"),
        )
        val groups = artistsOf(songs)
        assertEquals(listOf("DJ Mix"), groups.map { it.name })
    }

    @Test fun artistsMergeCaseVariants() {
        val songs = listOf(
            song("t1", artist = "daft punk"),
            song("t2", artist = "Daft Punk"),
        )
        val groups = artistsOf(songs)
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().songs.size)
    }
}
