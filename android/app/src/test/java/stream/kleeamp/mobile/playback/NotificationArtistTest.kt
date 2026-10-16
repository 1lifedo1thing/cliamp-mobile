package stream.kleeamp.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

class NotificationArtistTest {

    private fun station(
        source: StationSource,
        artist: String = "",
        album: String = "",
    ) = Station(
        id = "s",
        name = "s",
        url = "u",
        source = source,
        artist = artist,
        album = album,
    )

    @Test
    fun sourceEchoesNeverReachTheLockscreen() {
        assertEquals("unknown", PlaybackService.notificationArtist(station(StationSource.Local, artist = "my music")))
        assertEquals("unknown", PlaybackService.notificationArtist(station(StationSource.Directory)))
    }

    @Test
    fun cliampTracksNameTheirArtist() {
        assertEquals(
            "some artist",
            PlaybackService.notificationArtist(
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
        assertEquals("unknown", PlaybackService.notificationArtist(station(StationSource.Local)))
        assertEquals("unknown", PlaybackService.notificationArtist(station(StationSource.Podcast)))
    }

    @Test
    fun realMetadataPassesThrough() {
        assertEquals(
            "daft punk · discovery",
            PlaybackService.notificationArtist(
                station(StationSource.Local, artist = "daft punk", album = "discovery")
            ),
        )
        assertEquals(
            "some show",
            PlaybackService.notificationArtist(station(StationSource.Podcast, artist = "some show")),
        )
    }
}
