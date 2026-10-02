package stream.kleeamp.mobile.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import stream.kleeamp.mobile.theme.Mono
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Loads and decodes the bundled world-atlas geometry once, off the UI thread. */
@Composable
internal fun rememberWorldGeometry(): List<CountryGeometry>? {
    val context = LocalContext.current
    val state = produceState<List<CountryGeometry>?>(initialValue = null, context) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                context.assets.open("world-countries-110m.json").bufferedReader().use {
                    WorldAtlas.decode(it.readText())
                }
            }.getOrNull()
        }
    }
    return state.value
}

internal fun normalizeLon(lon: Float): Float {
    var v = lon % 360f
    if (v > 180f) v -= 360f
    if (v < -180f) v += 360f
    return v
}

/** Marker radius in dp, matching the website's 2.5..11px sqrt scaling. */
internal fun markerRadiusDp(value: Int, max: Int): Float =
    2.5f + sqrt(value.toFloat() / max.coerceAtLeast(1)) * (11f - 2.5f)

/**
 * The cliamp.stream listener globe, rebuilt for Compose Canvas: a dark
 * orthographic sphere with real country boundaries, a faint graticule,
 * depth-culled geography and markers, drag rotation and hover/tap country
 * identification. All colour comes from the theme palette; the geometry and
 * listener data stay identical across themes.
 */
@Composable
fun ListenerGlobe(
    rows: List<CountryListeners>,
    geometry: List<CountryGeometry>,
    selectedCode: String?,
    onSelect: (alpha2: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current

    // The website's rotation [-18, -14] centres the sphere on (18E, 14N).
    var centerLon by remember { mutableFloatStateOf(18f) }
    var centerLat by remember { mutableFloatStateOf(14f) }
    var dragging by remember { mutableStateOf(false) }
    var hovering by remember { mutableStateOf(false) }
    var hoverPx by remember { mutableStateOf(Offset.Unspecified) }
    var hoverLabel by remember { mutableStateOf<String?>(null) }
    var globeSize by remember { mutableStateOf(IntSize.Zero) }
    var time by remember { mutableFloatStateOf(0f) }

    fun globeRadiusPx(): Float {
        if (globeSize == IntSize.Zero) return 0f
        return minOf(globeSize.width, globeSize.height) / 2f - with(density) { 8.dp.toPx() }
    }

    // Join listener rows onto world-atlas ids, exactly like setData().
    val byId = remember(rows) {
        buildMap {
            rows.forEach { c -> atlasIdFor(c.code)?.let { put(it, c) } }
        }
    }
    val maxV = remember(rows) { rows.maxOfOrNull { it.listeners }?.coerceAtLeast(1) ?: 1 }
    val isLive = remember(rows) { rows.isNotEmpty() }
    val centroids = remember(geometry) {
        geometry.mapNotNull { g -> WorldAtlas.centroidOf(g)?.let { g.id to it } }.toMap()
    }
    val marks = remember(byId, centroids) {
        byId.mapNotNull { (id, c) ->
            centroids[id]?.let { Triple(c, it.lon, it.lat) }
        }.sortedBy { it.first.listeners }
    }
    val graticule = remember { WorldAtlas.graticule(10) }
    val idToCode = remember(rows) {
        rows.mapNotNull { c -> atlasIdFor(c.code)?.let { it to c.code } }.toMap()
    }

    fun labelFor(c: CountryListeners): String =
        c.name + "  " + "%,d".format(c.listeners) + if (isLive) " listening" else " sessions"

    /** Atlas id under the point, or null for water/unknown land. */
    fun hitTest(x: Float, y: Float): String? {
        val r = globeRadiusPx()
        if (r <= 0f) return null
        val w = globeSize.width.toFloat()
        val h = globeSize.height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val cLon = centerLon.toDouble()
        val cLat = centerLat.toDouble()
        // Front markers first, within 18pt like the website.
        val near = with(density) { 18.dp.toPx() }
        var bestId: String? = null
        var bestD = Double.MAX_VALUE
        marks.forEach { (c, lon, lat) ->
            val pr = WorldAtlas.project(lon, lat, cLon, cLat) ?: return@forEach
            val mx = (cx + r * pr.x).toFloat()
            val my = (cy - r * pr.y).toFloat()
            val d = hypot((mx - x).toDouble(), (my - y).toDouble())
            if (d < near && d < bestD) {
                bestD = d
                bestId = atlasIdFor(c.code)
            }
        }
        bestId?.let { return it }
        val ll = WorldAtlas.invert(
            x.toDouble(), y.toDouble(), cx.toDouble(), cy.toDouble(), r.toDouble(), cLon, cLat,
        ) ?: return null
        // Geographic hit-test, not nearest dot: the actual country polygon.
        geometry.firstOrNull { WorldAtlas.countryContains(it, ll) }?.let { g ->
            if (byId[g.id] != null) return g.id
        }
        return null
    }

    // Auto-rotation plus marker pulse, ~15fps like the throttled website loop.
    LaunchedEffect(Unit) {
        while (isActive) {
            kotlinx.coroutines.delay(66)
            time += 0.066f
            if (!dragging && !hovering) {
                centerLon = normalizeLon(centerLon + 0.64f)
            }
        }
    }

    // Hover labels resolve off the draw scope whenever the pointer moves.
    LaunchedEffect(hoverPx, hovering, centerLon, centerLat, rows, globeSize) {
        if (!hovering || hoverPx == Offset.Unspecified || globeSize == IntSize.Zero) {
            if (!hovering) hoverLabel = null
            return@LaunchedEffect
        }
        val x = hoverPx.x
        val y = hoverPx.y
        hoverLabel = withContext(Dispatchers.Default) {
            hitTest(x, y)?.let { byId[it]?.let(::labelFor) }
        }
    }

    // Theme-derived globe treatment; geometry never changes with the theme.
    val ocean = lerp(p.ground, p.ink, 0.03f)
    val land = lerp(p.ground, p.ink, 0.07f)
    val landLit = lerp(p.ground, p.ink, 0.14f)
    val coast = lerp(p.ground, p.ink, 0.20f)
    val gratColor = p.accent.copy(alpha = 0.10f)
    val edgeColor = p.accent.copy(alpha = 0.28f)
    val litStroke = p.accent.copy(alpha = 0.35f)
    val selectedId = selectedCode?.let(::atlasIdFor)

    Box(modifier.onSizeChanged { globeSize = it }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Enter, PointerEventType.Move -> {
                                    hovering = true
                                    hoverPx = event.changes.firstOrNull()?.position
                                        ?: Offset.Unspecified
                                }
                                PointerEventType.Exit -> {
                                    hovering = false
                                    hoverPx = Offset.Unspecified
                                    hoverLabel = null
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                .pointerInput(geometry, byId, centerLon, centerLat, globeSize) {
                    detectTapGestures { tap ->
                        onSelect(hitTest(tap.x, tap.y)?.let { idToCode[it] })
                    }
                }
                .pointerInput(density) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            hoverLabel = null
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, drag ->
                        change.consume()
                        // 0.3 degrees per dp, like the website's 0.3 per CSS px.
                        centerLon = normalizeLon(centerLon - drag.x / density.density * 0.3f)
                        centerLat = (centerLat + drag.y / density.density * 0.3f)
                            .coerceIn(-80f, 80f)
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val r = minOf(w, h) / 2f - 8.dp.toPx()
            if (r <= 0f) return@Canvas
            val cx = w / 2f
            val cy = h / 2f
            val cLon = centerLon.toDouble()
            val cLat = centerLat.toDouble()

            // Ocean sphere.
            drawCircle(ocean, r, Offset(cx, cy))
            // Graticule, front segments only.
            gratLines(graticule, cx, cy, r, cLon, cLat) { path ->
                drawPath(path, gratColor, style = Stroke(0.6.dp.toPx()))
            }
            // Countries: filled when fully front, stroked when crossing the limb.
            geometry.forEach { country ->
                val lit = byId[country.id]
                val selected = selectedId == country.id
                val fill = when {
                    selected -> p.accent.copy(alpha = 0.22f)
                    lit != null -> landLit
                    else -> land
                }
                val stroke = when {
                    selected -> p.accent
                    lit != null -> litStroke
                    else -> coast
                }
                val width = if (selected) 1.5.dp.toPx() else 0.6.dp.toPx()
                country.polygons.forEach { rings ->
                    rings.forEach { ring ->
                        globeRing(
                            ring, cx, cy, r, cLon, cLat,
                            onFill = { path -> drawPath(path, fill) },
                            onStroke = { path -> drawPath(path, stroke, style = Stroke(width)) },
                        )
                    }
                }
            }
            // Markers: smallest first so the largest lands on top.
            marks.forEach { (c, lon, lat) ->
                val pr = WorldAtlas.project(lon, lat, cLon, cLat) ?: return@forEach
                val mx = (cx + r * pr.x).toFloat()
                val my = (cy - r * pr.y).toFloat()
                val depth = 0.45f + 0.55f * pr.facing.coerceIn(0.0, 1.0).toFloat()
                val pulse = 1f + 0.06f * sin(time * 1.9f + lon.toFloat() * 0.05f)
                val rad = markerRadiusDp(c.listeners, maxV) * density.density * pulse
                drawCircle(p.accent.copy(alpha = 0.10f * depth), rad * 2.4f, Offset(mx, my))
                drawCircle(p.accent.copy(alpha = 0.9f * depth), rad, Offset(mx, my))
                drawCircle(
                    p.ground.copy(alpha = 0.85f), rad, Offset(mx, my),
                    style = Stroke(density.density),
                )
            }
            // Globe edge.
            drawCircle(edgeColor, r, Offset(cx, cy), style = Stroke(1.dp.toPx()))
        }

        // Hover tooltip follows the pointer, like the website's #tip.
        if (hovering && hoverLabel != null && !dragging) {
            Mono(
                hoverLabel!!,
                KleeampType.meta,
                p.ink,
                Modifier
                    .offset {
                        val x = if (hoverPx != Offset.Unspecified) hoverPx.x.roundToInt() else 0
                        val y = if (hoverPx != Offset.Unspecified) hoverPx.y.roundToInt() - 48 else 0
                        IntOffset(x - 60, y)
                    }
                    .padding(4.dp),
            )
        }
    }
}

/** Draws one ring: filled when every point is front, else front strokes only. */
private fun DrawScope.globeRing(
    ring: List<LatLon>,
    cx: Float, cy: Float, r: Float,
    centerLon: Double, centerLat: Double,
    onFill: (Path) -> Unit,
    onStroke: (Path) -> Unit,
) {
    if (ring.size < 3) return
    val pts = ring.map { WorldAtlas.project(it.lon, it.lat, centerLon, centerLat) }
    if (pts.all { it != null }) {
        onFill(Path().apply {
            pts.forEachIndexed { i, pr ->
                val x = cx + r * pr!!.x.toFloat()
                val y = cy - r * pr.y.toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        })
        return
    }
    // Limb crossing: stroke each fully-front segment so nothing wraps the disc.
    for (i in ring.indices) {
        val a = pts[i] ?: continue
        val b = pts[(i + 1) % ring.size] ?: continue
        onStroke(Path().apply {
            moveTo((cx + r * a.x).toFloat(), (cy - r * a.y).toFloat())
            lineTo((cx + r * b.x).toFloat(), (cy - r * b.y).toFloat())
        })
    }
}

/** Projects every graticule line, calling [draw] once per front run. */
private fun gratLines(
    lines: List<List<LatLon>>,
    cx: Float, cy: Float, r: Float,
    centerLon: Double, centerLat: Double,
    draw: (Path) -> Unit,
) {
    lines.forEach { line ->
        var path: Path? = null
        var lx = 0f
        var ly = 0f
        line.forEach { ll ->
            val pr = WorldAtlas.project(ll.lon, ll.lat, centerLon, centerLat)
            if (pr == null) {
                path?.let(draw)
                path = null
            } else {
                val x = (cx + r * pr.x).toFloat()
                val y = (cy - r * pr.y).toFloat()
                val cur = path
                // A jump across the disc means the line wrapped the limb.
                if (cur == null || hypot((x - lx).toDouble(), (y - ly).toDouble()) > r * 0.75) {
                    cur?.let(draw)
                    path = Path().apply { moveTo(x, y) }
                } else {
                    cur.lineTo(x, y)
                }
                lx = x
                ly = y
            }
        }
        path?.let(draw)
    }
}
