package stream.kleeamp.mobile.player

import org.junit.Assert.assertEquals
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

class ArtistOrTagLineTest {

    private fun station(
        source: StationSource,
        artist: String = "",
        album: String = "",
        tags: String = "",
    ) = Station(
        id = "s",
        name = "s",
        url = "u",
        source = source,
        artist = artist,
        album = album,
        tags = tags,
    )

    @Test
    fun sourceEchoesNeverReachTheArtistLine() {
        assertEquals("", artistOrTagLine(station(StationSource.Local, artist = "my music")))
        assertEquals("", artistOrTagLine(station(StationSource.Podcast, artist = "Podcast")))
        assertEquals(
            "synthwave",
            artistOrTagLine(station(StationSource.Directory, tags = "synthwave")),
        )
    }

    @Test
    fun realArtistsAndTagsPassThrough() {
        assertEquals(
            "daft punk · discovery",
            artistOrTagLine(station(StationSource.Local, artist = "daft punk", album = "discovery")),
        )
        // Tags split on commas and spaces alike, then rejoin.
        assertEquals(
            "pop · top · 40",
            artistOrTagLine(station(StationSource.Directory, tags = "pop, top 40")),
        )
        assertEquals("", artistOrTagLine(null))
    }
}
