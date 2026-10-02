package stream.kleeamp.mobile.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import stream.kleeamp.mobile.model.StationSource

class CliampChannelsTest {
    private val channelsBody = """
        {"stations": [
          {"id": "live", "name": "Live One", "stream": "https://radio.cliamp.stream/live/stream"},
          {"id": "lofi", "name": "Lofi", "stream": "https://radio.cliamp.stream/lofi/stream",
           "tracks": 2, "tracks_url": "https://radio.cliamp.stream/lofi/tracks"},
          {"id": " ", "name": "Blank", "stream": "https://radio.cliamp.stream/blank/stream"},
          {"id": "bad", "name": "Bad", "stream": "not a url",
           "tracks": 5, "tracks_url": "https://radio.cliamp.stream/bad/tracks"},
          {"id": "nolink", "name": "No Link", "stream": "https://radio.cliamp.stream/nolink/stream",
           "tracks": 5, "tracks_url": "not a url"}
        ]}
    """.trimIndent()

    @Test fun channelsPutSongsFirstAndDropBadRows() {
        val channels = CliampChannels.parseChannels(channelsBody)
        assertEquals(listOf("lofi", "live", "nolink"), channels.map { it.id })
        assertTrue(channels.first { it.id == "lofi" }.hasTracks)
        assertFalse(channels.first { it.id == "live" }.hasTracks)
        assertFalse(channels.first { it.id == "nolink" }.hasTracks)
    }

    @Test fun garbageBodyGivesNoChannels() {
        assertTrue(CliampChannels.parseChannels("not json").isEmpty())
        assertTrue(CliampChannels.parseChannels("").isEmpty())
        assertTrue(CliampChannels.parseChannels("{}").isEmpty())
    }

    private val tracksBody = """
        {"tracks": [
          {"id": "a1", "title": "First", "artist": "Al", "album": "Ab",
           "url": "https://radio.cliamp.stream/lofi/tracks/a1"},
          {"id": "a2", "title": "", "artist": "", "album": "",
           "url": "https://radio.cliamp.stream/lofi/tracks/a2",
           "cover": "https://example.com/a2.png"},
          {"id": "bad", "title": "Bad", "url": "file:///nope.mp3"}
        ]}
    """.trimIndent()

    @Test fun tracksMapToPlayerStations() {
        val channel = CliampChannels.Channel(id = "lofi", name = "Lofi", stream = "s")
        val tracks = CliampChannels.parseTracks(tracksBody, channel)
        assertEquals(2, tracks.size)
        val first = tracks[0]
        assertEquals("cliamp:lofi:a1", first.id)
        assertEquals("First", first.name)
        assertEquals(StationSource.Cliamp, first.source)
        assertEquals("lofi", first.slug)
        assertEquals("Al", first.artist)
        assertEquals("Ab", first.album)
        assertEquals("", first.cover)
        val second = tracks[1]
        assertEquals("untitled track", second.name)
        assertEquals("https://example.com/a2.png", second.cover)
    }

    @Test fun garbageTracksBodyGivesNoTracks() {
        val channel = CliampChannels.Channel(id = "lofi", name = "Lofi", stream = "s")
        assertTrue(CliampChannels.parseTracks("nope", channel).isEmpty())
    }
}
