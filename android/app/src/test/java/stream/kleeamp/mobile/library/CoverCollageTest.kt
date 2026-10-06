package stream.kleeamp.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Test
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

class CoverCollageTest {
    private fun song(id: String, cover: String = "") = Station(
        id = id,
        name = id,
        url = "content://media/$id",
        source = StationSource.Local,
        cover = cover,
    )

    @Test fun singleSongFillsAlone() {
        assertEquals(listOf("a"), pickCollage(listOf(song("a", "c1"))).map { it.id })
    }

    @Test fun twoAndThreeKeepEverySongInOrder() {
        val three = listOf(song("a", "c1"), song("b"), song("c", "c1"))
        assertEquals(listOf("a", "b", "c"), pickCollage(three).map { it.id })
    }

    @Test fun fourPlusPrefersDistinctCoversFirst() {
        val songs = listOf(
            song("a", "c1"),
            song("b", "c1"),
            song("c", "c2"),
            song("d"),
            song("e", "c3"),
            song("f", "c4"),
        )
        assertEquals(listOf("a", "c", "e", "f"), pickCollage(songs).map { it.id })
    }

    @Test fun coverlessGroupsStillFillFour() {
        val songs = (1..6).map { song("s$it") }
        assertEquals(listOf("s1", "s2", "s3", "s4"), pickCollage(songs).map { it.id })
    }
}
