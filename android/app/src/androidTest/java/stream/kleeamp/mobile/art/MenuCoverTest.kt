package stream.kleeamp.mobile.art

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

/**
 * The episode-menu regression: rows and prefetch warm the URL lane, while
 * the menu resolves through the station lane. Seeding only the URL key must
 * be enough for both the menu's first-frame peek and its async resolve to
 * return art with no network involved - before the lane-sharing fix the
 * station lane missed a warmed URL and the menu plated.
 */
@RunWith(AndroidJUnit4::class)
class MenuCoverTest {
    private val cover = "https://example.com/ep-cover-test.png"

    private fun episode() = Station(
        id = "pod:test:ep-cover-1",
        name = "Ep One",
        url = "https://example.com/ep-cover-1.mp3",
        source = StationSource.Podcast,
        cover = cover,
        artist = "Test Show",
    )

    @Test
    fun menuReadsRowWarmedUrl() = runBlocking {
        val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        StationArtSource.seedSmallForTest(cover, bmp)
        try {
            assertSame(bmp, ArtResolve.cachedSmall(episode()))
            assertSame(bmp, ArtResolve.small(episode(), ctx.contentResolver))
        } finally {
            StationArtSource.dropSmallForTest(cover)
            StationArtSource.dropSmallForTest(episode().id)
        }
    }
}
