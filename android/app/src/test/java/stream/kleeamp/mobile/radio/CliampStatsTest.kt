package stream.kleeamp.mobile.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CliampStatsTest {

    @Test
    fun decodesListenersAndPeak() {
        val stats = parseStats(
            """{"peak_listeners":120,"stations":{""" +
                """"lofi":{"active_listeners":7},""" +
                """"edm":{"active_listeners":5}}}""",
        )
        assertEquals(CliampStats(activeNow = 12, peak = 120), stats)
    }

    @Test
    fun toleratesMissingFields() {
        assertEquals(CliampStats(activeNow = 0, peak = 0), parseStats("{}"))
        assertNull(parseStats("not json"))
    }
}
