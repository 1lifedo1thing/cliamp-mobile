package stream.kleeamp.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

class EffectiveBoostTest {
    private fun station(source: StationSource) = Station(
        id = "s",
        name = "song",
        url = "https://x.test/s.mp3",
        source = source,
    )

    private val sections = mapOf(
        "Stations" to 0,
        "Podcasts" to 6,
        "Library" to 3,
    )

    @Test
    fun masterOffAlwaysGlobal() {
        assertEquals(4, effectiveBoostDb(4, sections, false, station(StationSource.Podcast)))
        assertEquals(4, effectiveBoostDb(4, sections, false, null))
    }

    @Test
    fun noStationFallsBackToGlobal() {
        assertEquals(4, effectiveBoostDb(4, sections, true, null))
    }

    @Test
    fun sectionValueWinsWhenScoped() {
        assertEquals(6, effectiveBoostDb(0, sections, true, station(StationSource.Podcast)))
        assertEquals(3, effectiveBoostDb(0, sections, true, station(StationSource.Local)))
        assertEquals(0, effectiveBoostDb(9, sections, true, station(StationSource.Directory)))
    }

    @Test
    fun missingSectionFallsBackToGlobal() {
        assertEquals(5, effectiveBoostDb(5, emptyMap(), true, station(StationSource.Podcast)))
    }

    @Test
    fun valuesClampToDeviceCeiling() {
        assertEquals(12, effectiveBoostDb(99, sections, false, null))
        assertEquals(0, effectiveBoostDb(-4, sections, false, null))
        assertEquals(12, effectiveBoostDb(0, mapOf("Podcasts" to 99), true, station(StationSource.Podcast)))
    }
}
