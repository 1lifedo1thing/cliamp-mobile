package stream.kleeamp.mobile.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
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
import kotlin.math.cos
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

/** Fly-to request: smoothly rotate the globe to centre this country. */
data class FlyTo(val code: String, val nonce: Long)

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
    /** Non-null (with a fresh nonce) flies the camera to that country. */
    flyTo: FlyTo? = null,
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
    // Last user touch: auto-rotation resumes IDLE_RESUME_MS after the
    // finger leaves, continuing from wherever the globe was left.
    var lastTouch by remember { mutableLongStateOf(0L) }
    // Fly-to animation state: a new drag cancels the flight.
    var animating by remember { mutableStateOf(false) }
    var animGen by remember { mutableIntStateOf(0) }

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
    // Flat lon/lat arrays decoded once: per-frame projection then works on
    // primitives with no allocations until the draw Path itself.
    val flatCountries = remember(geometry) {
        geometry.map { g ->
            g.id to g.polygons.flatMap { rings ->
                rings.map { ring ->
                    DoubleArray(ring.size * 2) { i ->
                        if (i % 2 == 0) ring[i / 2].lon else ring[i / 2].lat
                    }
                }
            }
        }
    }
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

    // Auto-rotation plus marker pulse at website cadence (~30fps). Pauses
    // while touched, hovered, flying, or within the idle delay after a touch.
    LaunchedEffect(Unit) {
        while (isActive) {
            kotlinx.coroutines.delay(33)
            time += 0.033f
            val idle = android.os.SystemClock.uptimeMillis() - lastTouch > IDLE_RESUME_MS
            if (!dragging && !hovering && !animating && idle) {
                centerLon = normalizeLon(centerLon + 0.32f)
            }
        }
    }

    // Fly-to: ease the camera onto the requested country's centroid along
    // the shortest longitude, so a leaderboard tap finds it on the globe.
    LaunchedEffect(flyTo) {
        val req = flyTo ?: return@LaunchedEffect
        val target = atlasIdFor(req.code)?.let { centroids[it] } ?: return@LaunchedEffect
        val gen = animGen + 1
        animGen = gen
        animating = true
        try {
            val startLon = centerLon
            val startLat = centerLat
            var dLon = (target.lon - startLon) % 360.0
            if (dLon > 180) dLon -= 360
            if (dLon < -180) dLon += 360
            val endLat = target.lat.coerceIn(-80.0, 80.0).toFloat()
            val t0 = android.os.SystemClock.uptimeMillis()
            while (true) {
                if (animGen != gen || dragging) break
                val t = ((android.os.SystemClock.uptimeMillis() - t0).toFloat() / 900f)
                    .coerceIn(0f, 1f)
                // Cosine ease in-out: leaves and arrives smoothly.
                val e = (0.5 - 0.5 * cos(t * kotlin.math.PI)).toFloat()
                centerLon = normalizeLon(startLon + (dLon * e).toFloat())
                centerLat = startLat + (endLat - startLat) * e
                if (t >= 1f) break
                kotlinx.coroutines.delay(16)
            }
        } finally {
            if (animGen == gen) animating = false
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

    // Latest tap logic without recreating the gesture detector: hitTest reads
    // rotation state at tap time, so taps always use the current globe angle.
    val tapHandler = rememberUpdatedState { tap: Offset ->
        lastTouch = android.os.SystemClock.uptimeMillis()
        onSelect(hitTest(tap.x, tap.y)?.let { idToCode[it] })
    }

    Box(modifier.onSizeChanged { globeSize = it }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            // Hover is a mouse concept. Touch drags also arrive
                            // as Move events; treating them as hover latched
                            // hovering on first touch and killed rotation.
                            val isMouse = event.changes.firstOrNull()?.type == PointerType.Mouse
                            when (event.type) {
                                PointerEventType.Enter, PointerEventType.Move -> {
                                    if (!isMouse) continue
                                    hovering = true
                                    hoverPx = event.changes.firstOrNull()?.position
                                        ?: Offset.Unspecified
                                }
                                PointerEventType.Exit -> {
                                    hovering = false
                                    hoverPx = Offset.Unspecified
                                    hoverLabel = null
                                }
                                PointerEventType.Press, PointerEventType.Release -> {
                                    if (!isMouse) {
                                        hovering = false
                                        hoverLabel = null
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                // Tap reads rotation fresh via the updated handler, so this
                // detector is created once and never restarted by rotation.
                .pointerInput(geometry, byId, globeSize) {
                    detectTapGestures { tap ->
                        tapHandler.value(tap)
                    }
                }
                .pointerInput(density) {
                    // Hold to rotate: a plain swipe in any direction belongs
                    // to the page (tab pager horizontally, list vertically),
                    // so the globe only claims the gesture after a long
                    // press. Once claimed, drags rotate both axes until
                    // release. 0.3 degrees per dp, like the website.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (awaitLongPressOrCancellation(down.id) == null) {
                            return@awaitEachGesture
                        }
                        dragging = true
                        animGen++
                        hoverLabel = null
                        lastTouch = android.os.SystemClock.uptimeMillis()
                        try {
                            var done = false
                            while (!done) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    done = true
                                } else {
                                    val delta = change.position - change.previousPosition
                                    change.consume()
                                    lastTouch = android.os.SystemClock.uptimeMillis()
                                    centerLon = normalizeLon(centerLon - delta.x / density.density * 0.3f)
                                    centerLat = (centerLat + delta.y / density.density * 0.3f)
                                        .coerceIn(-80f, 80f)
                                }
                                if (event.changes.all { !it.pressed }) done = true
                            }
                        } finally {
                            dragging = false
                        }
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
            // Graticule as a single stroked path.
            drawPath(gratPath(graticule, cx, cy, r, cLon, cLat), gratColor, style = Stroke(0.6.dp.toPx()))
            // Countries: one fill + one stroke path per paint group, so a
            // frame is a handful of draw calls no matter the ring count.
            val landFillPath = Path()
            val landStrokePath = Path()
            val litFillPath = Path()
            val litStrokePath = Path()
            val selFillPath = Path()
            val selStrokePath = Path()
            flatCountries.forEach { (id, rings) ->
                val lit = byId[id]
                val selected = selectedId == id
                val (fillDst, strokeDst) = when {
                    selected -> selFillPath to selStrokePath
                    lit != null -> litFillPath to litStrokePath
                    else -> landFillPath to landStrokePath
                }
                rings.forEach { pts ->
                    val (sub, front) = ringSubpath(pts, cx, cy, r, cLon, cLat)
                    if (front >= 3) fillDst.addPath(sub)
                    if (front >= 2) strokeDst.addPath(sub)
                }
            }
            if (!landFillPath.isEmpty) drawPath(landFillPath, land)
            if (!litFillPath.isEmpty) drawPath(litFillPath, landLit)
            if (!selFillPath.isEmpty) drawPath(selFillPath, p.accent.copy(alpha = 0.22f))
            if (!landStrokePath.isEmpty) drawPath(landStrokePath, coast, style = Stroke(0.6.dp.toPx()))
            if (!litStrokePath.isEmpty) drawPath(litStrokePath, litStroke, style = Stroke(0.6.dp.toPx()))
            if (!selStrokePath.isEmpty) {
                drawPath(selStrokePath, p.accent, style = Stroke(1.5.dp.toPx()))
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

/** Idle delay before auto-rotation resumes after a touch, milliseconds. */
private const val IDLE_RESUME_MS = 3000L

/**
 * Projects one flat ring once, returning its screen subpath and front-point
 * count. Callers batch subpaths into grouped paths, keeping a frame to a
 * handful of draw calls.
 */
private fun ringSubpath(
    pts: DoubleArray,
    cx: Float, cy: Float, r: Float,
    centerLon: Double, centerLat: Double,
): Pair<Path, Int> {
    val path = Path()
    var front = 0
    var started = false
    val n = pts.size / 2
    for (i in 0 until n) {
        val pr = WorldAtlas.project(pts[i * 2], pts[i * 2 + 1], centerLon, centerLat) ?: continue
        front++
        val x = cx + r * pr.x.toFloat()
        val y = cy - r * pr.y.toFloat()
        if (!started) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
    }
    path.close()
    return path to front
}

/** Projects every graticule line into one stroked path, front runs only. */
private fun gratPath(
    lines: List<List<LatLon>>,
    cx: Float, cy: Float, r: Float,
    centerLon: Double, centerLat: Double,
): Path {
    val out = Path()
    lines.forEach { line ->
        var started = false
        var lx = 0f
        var ly = 0f
        line.forEach { ll ->
            val pr = WorldAtlas.project(ll.lon, ll.lat, centerLon, centerLat)
            if (pr == null) {
                started = false
            } else {
                val x = (cx + r * pr.x).toFloat()
                val y = (cy - r * pr.y).toFloat()
                // A jump across the disc means the line wrapped the limb.
                if (!started || hypot((x - lx).toDouble(), (y - ly).toDouble()) > r * 0.75) {
                    out.moveTo(x, y)
                    started = true
                } else {
                    out.lineTo(x, y)
                }
                lx = x
                ly = y
            }
        }
    }
    return out
}
