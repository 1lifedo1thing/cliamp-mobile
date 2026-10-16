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
        assertEquals("", PlaybackService.notificationArtist(station(StationSource.Local, artist = "my music")))
        assertEquals("", PlaybackService.notificationArtist(station(StationSource.Podcast, artist = "Podcast")))
        assertEquals("", PlaybackService.notificationArtist(station(StationSource.Local)))
        assertEquals("", PlaybackService.notificationArtist(station(StationSource.Directory)))
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
