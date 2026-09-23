package stream.kleeamp.mobile.podcasts

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

class PodcastFeedTest {
    private val base = PodcastShow("feed:1", "", "https://example.com/feed.xml")
    private val factory = XmlPullParserFactory.newInstance()

    private suspend fun parse(feed: String) =
        PodcastFeed.parse(base, ByteArrayInputStream(feed.toByteArray())) { factory.newPullParser() }

    @Test
    fun fullItemMapsEveryField() = runTest {
        val loaded = parse(feed(item1(), itemNoAudio(), itemNoGuid(), itemOpus()))
        assertEquals(3, loaded.episodes.size)
        val ep = loaded.episodes[0]
        assertEquals("Ep One", ep.title)
        assertEquals("ep-1", ep.guid)
        assertEquals("https://example.com/ep1.mp3", ep.audioUrl)
        assertEquals(2_172_000L, ep.durationMs)
        assertEquals(1_735_725_600_000L, ep.publishedAt)
        assertEquals("https://example.com/ep1.jpg", ep.artwork)
        // Plain description wins when it comes first; the itunes title loses the same way.
        assertEquals("First", ep.description)
        assertEquals(3, ep.number)
        assertEquals(2, ep.season)
        assertEquals("full", ep.type)
        assertEquals("mp3", ep.codec)
    }

    @Test
    fun itemWithoutAudioIsDropped() = runTest {
        val loaded = parse(feed(itemNoAudio()))
        assertTrue(loaded.episodes.isEmpty())
    }

    @Test
    fun missingGuidFallsBackToAudioUrl() = runTest {
        val loaded = parse(feed(itemNoGuid()))
        assertEquals(listOf("https://example.com/ep3.mp3"), loaded.episodes.map { it.guid })
    }

    @Test
    fun emptyMimeFallsBackToExtensionForCodec() = runTest {
        val loaded = parse(feed(itemOpus()))
        assertEquals("opus", loaded.episodes.single().codec)
    }

    @Test
    fun channelFillsBlankShowFieldsOnly() = runTest {
        val loaded = parse(feed(item1()))
        assertEquals("Channel Title", loaded.show.title)
        assertEquals("Jane", loaded.show.author)
        assertEquals("https://example.com/channel.jpg", loaded.show.artwork)
        assertEquals("Technology", loaded.show.genre)
        assertEquals("Channel desc", loaded.show.description)
        assertEquals(1, loaded.show.episodeCount)
    }

    @Test
    fun existingShowFieldsWinOverChannel() = runTest {
        val mine = base.copy(title = "Mine", artwork = "mine.jpg", episodeCount = 5)
        val loaded = PodcastFeed.parse(mine, ByteArrayInputStream(feed(item1()).toByteArray())) {
            factory.newPullParser()
        }
        assertEquals("Mine", loaded.show.title)
        assertEquals("mine.jpg", loaded.show.artwork)
        assertEquals(5, loaded.show.episodeCount)
    }

    @Test
    fun emptyFeedHasNoEpisodesAndFallsBackToUntitled() = runTest {
        val loaded = PodcastFeed.parse(
            base.copy(feedUrl = "https://example.com/empty.xml"),
            ByteArrayInputStream(channelOnly("").toByteArray()),
        ) { factory.newPullParser() }
        assertTrue(loaded.episodes.isEmpty())
        assertEquals("untitled show", loaded.show.title)
    }

    @Test
    fun durationsCoverColonAndBareSecondsForms() {
        assertEquals(5_702_000L, PodcastFeed.parseDuration("01:35:02"))
        assertEquals(2_172_000L, PodcastFeed.parseDuration("36:12"))
        assertEquals(2_712_000L, PodcastFeed.parseDuration("2712"))
        assertEquals(0L, PodcastFeed.parseDuration(""))
        assertEquals(0L, PodcastFeed.parseDuration("abc"))
    }

    @Test
    fun datesCoverRfcAndIsoForms() {
        assertEquals(1_735_725_600_000L, PodcastFeed.parseDate("Wed, 01 Jan 2025 10:00:00 GMT"))
        assertEquals(1_735_787_045_000L, PodcastFeed.parseDate("2025-01-02T03:04:05Z"))
        assertEquals(0L, PodcastFeed.parseDate(""))
        assertEquals(0L, PodcastFeed.parseDate("not a date"))
    }

    private fun channelOnly(channelTitle: String) = """
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>$channelTitle</title>
          </channel>
        </rss>
    """.trimIndent()

    private fun feed(vararg items: String) = """
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>Channel Title</title>
            <itunes:author>Jane</itunes:author>
            <itunes:image href="https://example.com/channel.jpg"/>
            <itunes:summary>Channel desc</itunes:summary>
            <itunes:category text="Technology"/>
            <description>Fallback desc</description>
            ${items.joinToString("\n")}
          </channel>
        </rss>
    """.trimIndent()

    private fun item1() = """
        <item>
          <title>Ep One</title>
          <itunes:title>Ignored Title</itunes:title>
          <guid>ep-1</guid>
          <pubDate>Wed, 01 Jan 2025 10:00:00 GMT</pubDate>
          <description>First</description>
          <itunes:summary>Second</itunes:summary>
          <enclosure url="https://example.com/ep1.mp3" type="audio/mpeg"/>
          <enclosure url="https://example.com/ep1-art.jpg" type="image/jpeg"/>
          <itunes:duration>36:12</itunes:duration>
          <itunes:episode>3</itunes:episode>
          <itunes:season>2</itunes:season>
          <itunes:episodeType>full</itunes:episodeType>
          <itunes:image href="https://example.com/ep1.jpg"/>
        </item>
    """.trimIndent()

    private fun itemNoAudio() = """
        <item>
          <title>No Audio</title>
          <guid>ep-x</guid>
          <description>no enclosure</description>
        </item>
    """.trimIndent()

    private fun itemNoGuid() = """
        <item>
          <title>No Guid</title>
          <description>guid falls back</description>
          <enclosure url="https://example.com/ep3.mp3" type="audio/mpeg"/>
        </item>
    """.trimIndent()

    private fun itemOpus() = """
        <item>
          <title>Opus</title>
          <enclosure url="https://example.com/ep4.opus" type=""/>
        </item>
    """.trimIndent()
}
