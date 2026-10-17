package stream.kleeamp.mobile.player

import org.junit.Assert.assertEquals
import org.junit.Test
import stream.kleeamp.mobile.model.StationSource

class SourceLabelTest {

    @Test
    fun everySourceHasABadgeName() {
        assertEquals("cliamp radio", sourceLabel(StationSource.Cliamp))
        assertEquals("directory", sourceLabel(StationSource.Directory))
        assertEquals("on device", sourceLabel(StationSource.Local))
        assertEquals("provider", sourceLabel(StationSource.Provider))
        assertEquals("podcast", sourceLabel(StationSource.Podcast))
        assertEquals("custom", sourceLabel(StationSource.Custom))
    }
}
