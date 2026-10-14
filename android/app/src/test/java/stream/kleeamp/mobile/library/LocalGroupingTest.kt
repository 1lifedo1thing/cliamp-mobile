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
        track: Int = 0,
        disc: Int = 0,
    ) = Station(
        id = "local:$name",
        name = name,
        url = "file:///x/$name",
        source = StationSource.Local,
        artist = artist,
        album = album,
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

    @Test fun albumsGroupByNameAndArtist() {
        val songs = listOf(
            song("t1", artist = "a1", album = "hits", track = 2),
            song("t2", artist = "a1", album = "hits", track = 1),
            song("t3", artist = "a2", album = "hits", track = 1),
        )
        val groups = albumsOf(songs)
        assertEquals(2, groups.size)
        val first = groups.first { it.artist == "a1" }
        assertEquals(listOf("t2", "t1"), first.songs.map { it.name })
    }

    @Test fun artistsGroupAlphabetically() {
        val songs = listOf(song("t1", artist = "zebra"), song("t2", artist = "apple"))
        assertEquals(listOf("apple", "zebra"), artistsOf(songs).map { it.name })
    }
}
