package stream.kleeamp.mobile.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WorldAtlasTest {

    // A square island from two arcs: arc 0 out along the top-left half,
    // arc 1 home along the bottom-right half. Delta-encoded, like the real file.
    private val tiny = """
        {"type":"Topology","transform":{"scale":[1,1],"translate":[0,0]},
         "objects":{"countries":{"type":"GeometryCollection","geometries":[
           {"type":"Polygon","arcs":[[0,1]],"id":"840",
            "properties":{"name":"United States of America"}}]}},
         "arcs":[[[0,0],[0,10],[10,0]],[[10,10],[0,-10],[-10,0]]]}
    """.trimIndent()

    // Same arcs, second half addressed reversed (-2): proves ~i handling.
    private val tinyReversed = """
        {"type":"Topology","transform":{"scale":[1,1],"translate":[0,0]},
         "objects":{"countries":{"type":"GeometryCollection","geometries":[
           {"type":"Polygon","arcs":[[0,-2]],"id":"840",
            "properties":{"name":"United States of America"}}]}},
         "arcs":[[[0,0],[0,10],[10,0]],[[10,10],[0,-10],[-10,0]]]}
    """.trimIndent()

    @Test
    fun decodesArcsToRealPolygons() {
        val countries = WorldAtlas.decode(tiny)
        assertEquals(1, countries.size)
        val us = countries[0]
        assertEquals("840", us.id)
        val ring = us.polygons[0][0]
        // Closed square: arc 0 out, arc 1 home minus the shared joint.
        assertEquals(5, ring.size)
        assertEquals(0.0, ring[0].lon, 1e-9)
        assertEquals(0.0, ring[0].lat, 1e-9)
        assertTrue(WorldAtlas.countryContains(us, LatLon(5.0, 5.0)))
        assertTrue(!WorldAtlas.countryContains(us, LatLon(50.0, 50.0)))

        // Reversed addressing walks arc 1 backwards after the joint:
        // (0,0) (0,10) (10,10) then (10,0) (10,10) from the reversed arc.
        val rev = WorldAtlas.decode(tinyReversed)[0].polygons[0][0]
        assertEquals(5, rev.size)
        assertEquals(0.0, rev[0].lon, 1e-9)
        assertEquals(0.0, rev[0].lat, 1e-9)
        assertEquals(10.0, rev[3].lon, 1e-9)
        assertEquals(0.0, rev[3].lat, 1e-9)
    }

    @Test
    fun orthographicCullsTheFarSide() {
        // Centred on (0, 0): the centre projects to the middle, facing fully.
        val centre = WorldAtlas.project(0.0, 0.0, 0.0, 0.0)
        assertNotNull(centre)
        assertEquals(0.0, centre!!.x, 1e-9)
        assertEquals(0.0, centre.y, 1e-9)
        assertEquals(1.0, centre.facing, 1e-9)
        // Antipode is behind the globe, never drawn.
        assertNull(WorldAtlas.project(180.0, 0.0, 0.0, 0.0))
        // Limb still projects; just past it does not.
        assertNotNull(WorldAtlas.project(89.0, 0.0, 0.0, 0.0))
        assertNull(WorldAtlas.project(91.0, 0.0, 0.0, 0.0))
    }

    @Test
    fun inverseRoundTripsThroughTheCentre() {
        val p = WorldAtlas.invert(100.0, 100.0, 100.0, 100.0, 80.0, 18.0, 14.0)
        assertNotNull(p)
        assertEquals(18.0, p!!.lon, 1e-9)
        assertEquals(14.0, p.lat, 1e-9)
        // Outside the disc hit-tests as water.
        assertNull(WorldAtlas.invert(0.0, 0.0, 100.0, 100.0, 80.0, 18.0, 14.0))
    }

    @Test
    fun centroidStaysInsideTheGeometry() {
        val countries = WorldAtlas.decode(tiny)
        val c = WorldAtlas.centroidOf(countries[0])
        assertNotNull(c)
        assertEquals(5.0, c!!.lon, 1e-6)
        assertEquals(5.0, c.lat, 1e-6)
    }

    @Test
    fun graticuleMatchesWebsiteDensity() {
        // 36 meridians + 17 parallels + the polar cap = d3 Graticule10 shape.
        val lines = WorldAtlas.graticule(10)
        assertEquals(36 + 17 + 1, lines.size)
        assertTrue(lines.all { it.size >= 80 })
    }

    @Test
    fun isoMappingCoversListenerCountries() {
        assertEquals("840", atlasIdFor("US"))
        assertEquals("276", atlasIdFor("DE"))
        assertEquals("826", atlasIdFor("GB"))
        assertEquals("392", atlasIdFor("JP"))
        assertEquals("076", atlasIdFor("BR"))
        assertEquals(null, atlasIdFor("XX"))
        // Every mapped id is a zero-padded 3-digit world-atlas id.
        assertTrue(Alpha2ToNumeric.values.all { it in 0..999 })
        assertTrue(abs(Alpha2ToNumeric.size - 177) < 40)
    }
}
