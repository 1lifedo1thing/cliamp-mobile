package stream.kleeamp.mobile.radio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One geographic point, degrees. */
data class LatLon(val lon: Double, val lat: Double)

/** A country's real geometry: polygons of rings of points, degrees. */
data class CountryGeometry(
    val id: String,
    val name: String,
    val polygons: List<List<List<LatLon>>>,
)

/** Orthographic forward projection in unit-sphere coordinates. */
data class Projected(val x: Double, val y: Double, val facing: Double)

/**
 * Real world geometry plus the spherical orthographic math cliamp.stream
 * uses (d3.geoOrthographic with clipAngle 90).
 *
 * [decode] turns a world-atlas v2 Topology document (countries-110m) into
 * country polygons. Everything else is pure projection/hit-test math so it
 * is unit-tested on the JVM without Compose.
 */
object WorldAtlas {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Decodes every Polygon/MultiPolygon in `objects.countries`. */
    fun decode(topologyJson: String): List<CountryGeometry> {
        val root = json.parseToJsonElement(topologyJson).jsonObject
        val transform = root["transform"]?.jsonObject
        val scale = transform?.get("scale")?.jsonArray
        val translate = transform?.get("translate")?.jsonArray
        val sx = scale?.get(0)?.jsonPrimitive?.double ?: 1.0
        val sy = scale?.get(1)?.jsonPrimitive?.double ?: 1.0
        val tx = translate?.get(0)?.jsonPrimitive?.double ?: 0.0
        val ty = translate?.get(1)?.jsonPrimitive?.double ?: 0.0

        val rawArcs = root["arcs"]?.jsonArray ?: return emptyList()
        // Decoded arcs as lon/lat point lists, delta-decoded and transformed.
        val arcs: List<List<LatLon>> = rawArcs.map { arc ->
            var x = 0
            var y = 0
            arc.jsonArray.map { pt ->
                x += pt.jsonArray[0].jsonPrimitive.int
                y += pt.jsonArray[1].jsonPrimitive.int
                LatLon(x * sx + tx, y * sy + ty)
            }
        }
        fun arcPoints(index: Int): List<LatLon> =
            // Negative TopoJSON indices (~i) address arc (-i - 1) reversed.
            if (index >= 0) arcs[index] else arcs[-index - 1].asReversed()

        val countries = root["objects"]?.jsonObject?.get("countries")
            ?.jsonObject?.get("geometries")?.jsonArray ?: return emptyList()
        return countries.mapNotNull { g ->
            val obj = g.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.content ?: ""
            val name = obj["properties"]?.jsonObject?.get("name")
                ?.jsonPrimitive?.content ?: id
            val arcRefs = obj["arcs"]?.jsonArray ?: return@mapNotNull null
            val polygons: List<List<List<LatLon>>> = when (type) {
                "Polygon" -> listOf(ringsOf(arcRefs, ::arcPoints))
                "MultiPolygon" -> arcRefs.map { poly -> ringsOf(poly.jsonArray, ::arcPoints) }
                else -> return@mapNotNull null
            }.filter { it.isNotEmpty() }
            if (polygons.isEmpty()) null else CountryGeometry(id, name, polygons)
        }
    }

    private fun ringsOf(
        refs: kotlinx.serialization.json.JsonArray,
        arcPoints: (Int) -> List<LatLon>,
    ): List<List<LatLon>> = refs.map { ring ->
        val out = mutableListOf<LatLon>()
        ring.jsonArray.forEach { ref ->
            val pts = arcPoints(ref.jsonPrimitive.int)
            if (out.isEmpty()) out.addAll(pts)
            else out.addAll(pts.drop(1))
        }
        out
    }

    /**
     * Forward orthographic projection with the sphere centred on
     * ([centerLon], [centerLat]). Returns null for the far hemisphere,
     * exactly like clipAngle 90: far-side geography is never drawn, so a
     * back-side country or marker cannot render like a front-side one.
     */
    fun project(lon: Double, lat: Double, centerLon: Double, centerLat: Double): Projected? {
        val lambda = (lon - centerLon) * PI / 180.0
        val phi = lat * PI / 180.0
        val phi0 = centerLat * PI / 180.0
        val cosPhi = cos(phi)
        val facing = sin(phi0) * sin(phi) + cos(phi0) * cosPhi * cos(lambda)
        if (facing <= 0.0) return null
        return Projected(
            x = cosPhi * sin(lambda),
            y = cos(phi0) * sin(phi) - sin(phi0) * cosPhi * cos(lambda),
            facing = facing,
        )
    }

    /**
     * Inverse projection: screen point back to lon/lat. [px]/[py] are
     * pixels, ([cx], [cy]) the globe centre, [r] its radius. Null outside
     * the disc, which hit-tests as water.
     */
    fun invert(
        px: Double, py: Double,
        cx: Double, cy: Double, r: Double,
        centerLon: Double, centerLat: Double,
    ): LatLon? {
        val x = (px - cx) / r
        val y = (cy - py) / r
        val rho2 = x * x + y * y
        if (rho2 > 1.0 || r <= 0.0) return null
        val rho = sqrt(rho2)
        if (rho == 0.0) return LatLon(centerLon, centerLat)
        val c = asin(rho.coerceIn(-1.0, 1.0))
        val phi0 = centerLat * PI / 180.0
        val lat = asin(
            (cos(c) * sin(phi0) + y * sin(c) * cos(phi0) / rho).coerceIn(-1.0, 1.0),
        ) * 180.0 / PI
        val lon = centerLon + atan2(
            x * sin(c),
            rho * cos(phi0) * cos(c) - y * sin(phi0) * sin(c),
        ) * 180.0 / PI
        return LatLon(lon, lat)
    }

    /** True when [p] lies inside [ring] (ray casting on the lon/lat plane). */
    fun ringContains(ring: List<LatLon>, p: LatLon): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) &&
                p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** True when [p] is inside any ring of any polygon of [country]. */
    fun countryContains(country: CountryGeometry, p: LatLon): Boolean =
        country.polygons.any { rings -> rings.any { ringContains(it, p) } }

    /**
     * Planar area-weighted centroid of the country's largest polygon, for
     * country-level listener markers. Real geometry in, real location out;
     * nothing is hardcoded or random.
     */
    fun centroidOf(country: CountryGeometry): LatLon? {
        var best: Pair<Double, LatLon>? = null
        country.polygons.forEach { rings ->
            val ring = rings.maxByOrNull { abs(ringArea(it)) } ?: return@forEach
            val area = ringArea(ring)
            if (area == 0.0) return@forEach
            var cx = 0.0
            var cy = 0.0
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[(i + 1) % ring.size]
                val cross = a.lon * b.lat - b.lon * a.lat
                cx += (a.lon + b.lon) * cross
                cy += (a.lat + b.lat) * cross
            }
            val c = LatLon(cx / (6 * area), cy / (6 * area))
            val prev = best
            if (prev == null || abs(area) > prev.first) best = abs(area) to c
        }
        return best?.second
    }

    private fun ringArea(ring: List<LatLon>): Double {
        var sum = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            sum += a.lon * b.lat - b.lon * a.lat
        }
        return sum / 2.0
    }

    /**
     * Graticule lines every [step] degrees, matching d3.geoGraticule10's
     * density. Meridians span -84..84 (the poles collapse on a globe);
     * parallels span the full width. Sampled every 2° so each segment
     * projects cleanly around the limb.
     */
    fun graticule(step: Int = 10): List<List<LatLon>> {
        val lines = mutableListOf<List<LatLon>>()
        var lon = -180
        while (lon < 180) {
            lines += (-84..84 step 2).map { LatLon(lon.toDouble(), it.toDouble()) }
            lon += step
        }
        var lat = -80
        while (lat <= 80) {
            lines += (-180..180 step 2).map { LatLon(it.toDouble(), lat.toDouble()) }
            lat += step
        }
        lines += (-180..180 step 2).map { LatLon(it.toDouble(), 84.0) }
        return lines
    }
}
