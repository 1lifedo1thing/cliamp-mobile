package stream.kleeamp.mobile.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationTest {
    private fun station(source: StationSource, id: String) = Station(
        id = id,
        name = "n",
        url = "https://example.com/x",
        source = source,
    )

    @Test fun channelTrackIdIsATrack() {
        val track = station(StationSource.Cliamp, "cliamp:edm:42")
        assertTrue(track.isChannelTrack)
        assertTrue(track.isTrack)
    }

    @Test fun liveCliampStreamIsNotATrack() {
        val live = station(StationSource.Cliamp, "cliamp:edm")
        assertFalse(live.isChannelTrack)
        assertFalse(live.isTrack)
    }

    @Test fun localProviderAndPodcastStayTracks() {
        assertTrue(station(StationSource.Local, "l1").isTrack)
        assertTrue(station(StationSource.Provider, "p1").isTrack)
        assertTrue(station(StationSource.Podcast, "e1").isTrack)
    }

    @Test fun directoryAndCustomStayStreams() {
        assertFalse(station(StationSource.Directory, "d1").isTrack)
        assertFalse(station(StationSource.Custom, "c1").isTrack)
    }
}
