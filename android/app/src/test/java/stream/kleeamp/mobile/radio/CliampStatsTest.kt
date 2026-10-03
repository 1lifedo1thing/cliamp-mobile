package stream.kleeamp.mobile.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliampStatsTest {

    @Test
    fun decodesListenersAndPeak() {
        val stats = parseStats(
            """{"peak_listeners":120,"stations":{""" +
                """"lofi":{"active_listeners":7},""" +
                """"edm":{"active_listeners":5}}}""",
        )
        assertEquals(12, stats?.activeNow)
        assertEquals(120, stats?.peak)
    }

    @Test
    fun toleratesMissingFields() {
        assertEquals(CliampStats(activeNow = 0, peak = 0), parseStats("{}"))
        assertNull(parseStats("not json"))
    }

    @Test
    fun aggregatesPerStationAndCountriesLikeTheWebsite() {
        val stats = aggregateStats(
            """{"peak_listeners":280,"stations":{""" +
                """"lofi":{"active_listeners":2,"active_listener_countries":""" +
                """[{"country_code":"US","country":"United States","sessions":2}],""" +
                """"top_countries":[{"country_code":"US","country":"United States","sessions":10}]},""" +
                """"edm":{"active_listeners":1,"active_listener_countries":""" +
                """[{"country_code":"DE","country":"Germany","sessions":1}],""" +
                """"top_countries":[{"country_code":"DE","country":"Germany","sessions":5}]}}}""",
            """{"stations":{"lofi":{"active_listeners":3,"active_listener_countries":""" +
                """[{"country_code":"US","country":"United States","listeners":3}]}}}""",
        )
        checkNotNull(stats)
        // Per-station counts add both sources, like paintChannels.
        assertEquals(5, stats.perStation["lofi"])
        assertEquals(1, stats.perStation["edm"])
        assertEquals(6, stats.activeNow)
        assertEquals(3, stats.onPlaylists)
        // Live actives drive the ranking while any exist.
        assertTrue(stats.isLive)
        assertEquals(
            listOf(CountryListeners("US", "United States", 5), CountryListeners("DE", "Germany", 1)),
            stats.countries,
        )
        assertEquals(1, stats.listenersFor("edm"))
        assertEquals(0, stats.listenersFor("unknown"))
    }

    @Test
    fun boostsOwnCountryInRankOrder() {
        val rows = listOf(
            CountryListeners("US", "United States", 5),
            CountryListeners("DE", "Germany", 2),
        )
        // Present row gains one and re-sorts.
        assertEquals(
            listOf(CountryListeners("US", "United States", 5), CountryListeners("DE", "Germany", 3)),
            boostCountryRows(rows, "DE"),
        )
        // Absent country joins with a locale display name.
        val joined = boostCountryRows(rows, "IR")
        assertEquals(3, joined.size)
        assertEquals("IR", joined.last().code)
        assertEquals(1, joined.last().listeners)
        assertTrue(joined.last().name.isNotBlank())
        // Null stays untouched; unknown codes never resolve.
        assertEquals(rows, boostCountryRows(rows, null))
        assertEquals(null, resolveCountryCode("XX"))
        assertEquals(null, resolveCountryCode(""))
        assertEquals(null, resolveCountryCode(null))
        assertEquals("DE", resolveCountryCode("de"))
        assertEquals("US", resolveCountryCode(" us "))
    }

    @Test
    fun fallsBackToAllTimeWithoutLiveListeners() {
        val stats = aggregateStats(
            """{"peak_listeners":280,"stations":{""" +
                """"lofi":{"active_listeners":0,"active_listener_countries":[],""" +
                """"top_countries":[{"country_code":"US","country":"United States","sessions":10}]}}}""",
            null,
        )
        checkNotNull(stats)
        assertEquals(false, stats.isLive)
        assertEquals(listOf(CountryListeners("US", "United States", 10)), stats.countries)
        // Empty per-station map means "no data yet", not "quiet".
        assertEquals(null, aggregateStats("{}", null)?.listenersFor("lofi"))
    }
}
