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
        assertEquals(
            "discovery",
            artistOrTagLine(station(StationSource.Local, artist = "my music", album = "discovery")),
        )
        assertEquals("unknown", artistOrTagLine(station(StationSource.Podcast, artist = "Podcast")))
        assertEquals(
            "synthwave",
            artistOrTagLine(station(StationSource.Directory, tags = "synthwave")),
        )
    }

    @Test
    fun cliampTracksNameTheirArtist() {
        assertEquals(
            "some artist",
            artistOrTagLine(
                Station(
                    id = "cliamp:ch:1",
                    name = "track",
                    url = "u",
                    source = StationSource.Cliamp,
                    artist = "some artist",
                )
            ),
        )
    }

    @Test
    fun aMissingArtistReadsUnknownNeverEmpty() {
        assertEquals("unknown", artistOrTagLine(station(StationSource.Directory)))
        assertEquals(
            "unknown",
            artistOrTagLine(station(StationSource.Local, artist = "my music", album = "my music")),
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
