package stream.kleeamp.mobile.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay
import stream.kleeamp.mobile.chrome.ArtKind
import stream.kleeamp.mobile.chrome.rememberArt
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.theme.LocalPalette
import kotlin.math.abs
import kotlin.math.max

/**
 * The Now Playing backdrop: ground at the base, an ambient wash tinted by
 * the current cover on top, and a fade back to ground at the bottom so the
 * meter and keys stay legible. The wash melts between stations instead of
 * cutting, which is where the depth comes from.
 *
 * The tint reads the thumb, not the hero: thumbs are prefetched and cached
 * for the queue neighbours, so the color is usually already in memory and
 * never fires its own network fetch. Coverless stations fall back to the
 * accent, matching the halo behind the plate.
 */
@Composable
internal fun rememberPlayerAmbient(station: Station?): androidx.compose.runtime.State<Color> {
    val p = LocalPalette.current
    val art = rememberArt(station = station, kind = ArtKind.Thumb)
    val average = remember(art) { samplePixels(art)?.let(::ambientAverage) }
    val target = remember(average, p.accent, p.ground, p.dark) {
        ambientTarget(average, p.accent, p.ground, p.dark)
    }
    return animateColorAsState(target, tween(900), label = "playerAmbient")
}

@Composable
internal fun PlayerBackdrop(
    ambient: Color,
    playing: Boolean,
    spectrum: () -> FloatArray?,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    // Slow breathe around the wash core, the same language as the plate's
    // swell: a layer transform, so the GPU moves pixels without redrawing
    // the gradient every frame.
    val breathTransition = rememberInfiniteTransition(label = "ambientBreath")
    val breath by breathTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ambientBreath",
    )
    // The motion belongs to playback: pausing eases the wash back to still
    // instead of freezing mid-swell.
    val gate by animateFloatAsState(
        if (playing) 1f else 0f,
        tween(600),
        label = "ambientGate",
    )
    // Music energy follows the low bands with a fast attack and a slow
    // release, so the wash surges smoothly with the track and settles
    // gently into pauses instead of snapping. Shared with the mini bar.
    val energy = rememberMusicEnergy(playing, spectrum)
    Box(modifier.background(p.ground)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val radius = max(constraints.maxWidth, constraints.maxHeight).toFloat()
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1f + 0.07f * breath * gate + 0.05f * energy
                        scaleY = 1f + 0.07f * breath * gate + 0.05f * energy
                        alpha = (0.88f + 0.12f * breath * gate + 0.30f * energy).coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(0.5f, 0.26f)
                    }
                    .background(
                        Brush.radialGradient(
                            0f to ambient.copy(alpha = 0.72f),
                            0.7f to ambient.copy(alpha = 0.22f),
                            1f to Color.Transparent,
                            center = Offset(
                                constraints.maxWidth * 0.5f,
                                constraints.maxHeight * 0.26f,
                            ),
                            radius = radius * 0.8f,
                        ),
                    ),
            )
        }
        // Grounding fade: the wash lives behind the art, the meter and the
        // transport sit on the page. It reaches ground well above them, or
        // the tint muddies the bricks - legibility first, glow second.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        0.85f to p.ground,
                        1f to p.ground,
                    ),
                ),
        )
    }
}

/** Music energy in 0..1 from the low bands, driving the wash surge. Pure, pinned by test. */
internal fun spectrumEnergy(bands: FloatArray?): Float {
    if (bands == null || bands.isEmpty()) return 0f
    val n = minOf(bands.size, 24)
    var sum = 0f
    for (i in 0 until n) sum += bands[i].coerceIn(0f, 1f)
    return (sum / n * 1.6f).coerceIn(0f, 1f)
}

/**
 * Smoothed music energy shared by the backdrop and the mini bar: polls the
 * spectrum ~20 times a second with a fast attack and a slow release, so
 * motion surges with the track and eases out of pauses. The level
 * normalizes against a slowly-decaying recent peak, so quiet and loud
 * masters alike swing the full 0..1 instead of hovering near zero - a
 * fixed gain would fit one track and starve the next.
 */
@Composable
internal fun rememberMusicEnergy(playing: Boolean, spectrum: () -> FloatArray?): Float {
    var energy by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        while (true) {
            delay(50)
            val raw = if (playing) spectrumEnergy(spectrum()) else 0f
            peak = maxOf(raw, peak * 0.997f)
            val norm = if (peak > 0.08f) (raw / peak).coerceIn(0f, 1f) else 0f
            val k = if (norm > energy) 0.4f else 0.10f
            energy += (norm - energy) * k
        }
    }
    return energy
}

/** Stride-sampled pixels for tinting, or null when there is no art. */
private fun samplePixels(art: ImageBitmap?): IntArray? {
    if (art == null) return null
    return runCatching {
        val w = art.width
        val h = art.height
        if (w <= 0 || h <= 0) return null
        val buf = IntArray(w * h)
        art.readPixels(buf, 0, 0, w, h, 0, w)
        val stride = max(1, (buf.size + 2047) / 2048)
        IntArray((buf.size + stride - 1) / stride) { i -> buf[i * stride] }
    }.getOrNull()
}

/** Plain sRGB mean of sampled ARGB pixels. */
internal fun ambientAverage(pixels: IntArray): Color {
    if (pixels.isEmpty()) return Color.Transparent
    var r = 0L
    var g = 0L
    var b = 0L
    for (px in pixels) {
        r += (px shr 16) and 0xFF
        g += (px shr 8) and 0xFF
        b += px and 0xFF
    }
    val n = pixels.size.toFloat()
    return Color(r / n / 255f, g / n / 255f, b / n / 255f, 1f)
}

/**
 * What the wash is painted from: the art's average with its hue kept but
 * the extremes tamed (gray covers fall back to the accent, neon gets
 * reined in), then seated into the theme - deepened toward ground in dark,
 * a soft tint of ground in light, so ink always reads over it.
 */
internal fun ambientTarget(average: Color?, accent: Color, ground: Color, dark: Boolean): Color {
    val avgHsv = average?.let(::rgbToHsv)
    val base = if (average != null && avgHsv != null && avgHsv[1] >= 0.15f) average else accent
    val hsv = rgbToHsv(base) ?: return accent
    hsv[1] = hsv[1].coerceIn(0.35f, 0.85f)
    return if (dark) {
        hsv[2] = (hsv[2] * 0.55f).coerceIn(0f, 1f)
        lerp(hsvToRgb(hsv), ground, 0.22f)
    } else {
        lerp(hsvToRgb(hsv), ground, 0.60f)
    }
}

/** RGB to HSV, hand-rolled so the math stays JVM-testable without android.graphics. */
internal fun rgbToHsv(c: Color): FloatArray? {
    val r = c.red
    val g = c.green
    val b = c.blue
    if (r.isNaN() || g.isNaN() || b.isNaN()) return null
    val lo = minOf(r, g, b)
    val hi = maxOf(r, g, b)
    val d = hi - lo
    val h = when {
        d == 0f -> 0f
        hi == r -> 60f * (((g - b) / d) % 6f)
        hi == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    val s = if (hi == 0f) 0f else d / hi
    return floatArrayOf(((h % 360f) + 360f) % 360f, s, hi)
}

internal fun hsvToRgb(hsv: FloatArray): Color {
    val h = hsv[0]
    val s = hsv[1].coerceIn(0f, 1f)
    val v = hsv[2].coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m, 1f)
}
