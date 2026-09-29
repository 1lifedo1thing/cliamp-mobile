package stream.kleeamp.mobile.settings

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import stream.kleeamp.mobile.db.KleeampDatabase
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.prefs.Prefs

/**
 * Radio banking end to end minus the network: ticks drive onTick the way the
 * player's 2 Hz poller does, a title change banks the heard song into the
 * outbox, and a shapeless jingle title never counts. The floor is narrowed
 * so the suite takes seconds instead of minutes, and the user token is
 * parked for the duration so nothing reaches ListenBrainz.
 */
@RunWith(AndroidJUnit4::class)
class RadioScrobbleTest {
    private lateinit var ctx: Context
    private lateinit var prefs: Prefs
    private lateinit var db: KleeampDatabase
    private lateinit var scope: CoroutineScope
    private var savedToken = ""
    private var savedRadio = false

    @Before
    fun setUp() = runBlocking {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = Prefs(ctx)
        savedToken = prefs.listenBrainzTokenSync()
        savedRadio = prefs.scrobbleRadio.first()
        prefs.setListenBrainzToken("")
        prefs.setScrobbleRadio(true)
        db = Room.inMemoryDatabaseBuilder(ctx, KleeampDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @After
    fun tearDown() = runBlocking {
        runCatching { db.close() }
        scope.cancel()
        prefs.setListenBrainzToken(savedToken)
        prefs.setScrobbleRadio(savedRadio)
    }

    private fun station() = Station(
        id = "test-fm",
        name = "Test FM",
        url = "https://example.com/test-fm",
        source = StationSource.Custom,
    )

    private suspend fun playTitle(scrobbler: Scrobbler, title: String, ms: Long) {
        val s = station()
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            scrobbler.onTick(s, true, 0L, title)
            delay(200)
        }
    }

    private suspend fun awaitPending(scrobbler: Scrobbler): Int =
        withTimeout(5_000) { scrobbler.pendingCount.first { it > 0 } }

    @Test
    fun titleChangeBanksHeardSong() = runBlocking {
        val scrobbler =
            Scrobbler(ctx, prefs, scope, db.stats(), db.scrobbles(), radioMinMs = 1_000L)
        delay(500) // let the radio opt-in collector catch up
        playTitle(scrobbler, "Singer A - Song A", 1_800L)
        scrobbler.onTick(station(), true, 0L, "Singer B - Song B")
        assertEquals(1, awaitPending(scrobbler))
        val row = db.scrobbles().due(Long.MAX_VALUE).single()
        assertEquals("Singer A", row.artist)
        assertEquals("Song A", row.title)
    }

    @Test
    fun shapelessTitleNeverCounts() = runBlocking {
        val scrobbler =
            Scrobbler(ctx, prefs, scope, db.stats(), db.scrobbles(), radioMinMs = 1_000L)
        delay(500) // let the radio opt-in collector catch up
        // Station idle with no Artist - Title shape: nothing trackable.
        playTitle(scrobbler, "Test FM", 1_800L)
        scrobbler.onTick(station(), true, 0L, "Singer B - Song B")
        delay(1_000) // a banked listen would have landed by now
        assertEquals(0, db.scrobbles().due(Long.MAX_VALUE).size)
    }
}
