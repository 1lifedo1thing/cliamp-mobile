package stream.kleeamp.mobile.art

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * A generated stand-in for missing cover art: a deep jewel-tone gradient in
 * the theme's accent family, a seeded geometric motif, and the item's
 * monogram. The pick is stable per key, so a station keeps the same plate
 * across recompositions and launches - the same promise the bundled PNGs
 * made, with no assets, no warm-up and no megabyte of PNGs.
 *
 * Same branches as real art: [key] must match the row's key (station id,
 * never a rotating signed URL) so the player hero and the list row wear the
 * same plate for one item.
 */
@Composable
fun SeedPlate(
    key: String,
    name: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    caption: String? = null,
    radius: Dp = KleeampShape.medium,
) {
    val p = LocalPalette.current
    val look = remember(key, p.accent, p.ground) { seedLook(key, p.accent, p.ground) }
    val glyph = remember(name) { initialOf(name) }
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .border(1.dp, p.artBorder, RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(look.gradient)
            drawMotif(look.variant, look.motifSeed, look.motifInk)
        }
        if (icon != null) {
            androidx.compose.material3.Icon(
                icon,
                name,
                Modifier.fillMaxSize(0.45f),
                tint = Color.White.copy(alpha = 0.92f),
            )
        } else if (glyph != null) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val size = maxWidth.coerceAtMost(maxHeight) * 0.44f
                with(LocalDensity.current) {
                    Mono(
                        glyph,
                        KleeampType.rowPrimaryMedium.copy(
                            fontSize = size.toPx().toSp(),
                            lineHeight = size.toPx().toSp(),
                        ),
                        Color.White.copy(alpha = 0.92f),
                        Modifier.align(Alignment.Center),
                        maxLines = 1,
                    )
                }
            }
        }
        if (caption != null) {
            Mono(
                caption,
                KleeampType.meta.copy(letterSpacing = 0.1.em),
                Color.White.copy(alpha = 0.75f),
                Modifier.align(Alignment.BottomStart).padding(14.dp),
                maxLines = 1,
            )
        }
    }
}

/** Stable non-negative bucket for [key]; String.hashCode is specified stable. */
internal fun seedVariant(key: String, buckets: Int): Int {
    val h = key.hashCode()
    return ((h % buckets) + buckets) % buckets
}

/** The first letter of an item's name, for its plate monogram. */
internal fun initialOf(name: String?): String? {
    if (name.isNullOrBlank()) return null
    return (name.firstOrNull { it.isLetter() }
        ?: name.firstOrNull { !it.isWhitespace() })
        ?.uppercaseChar()?.toString()
}

/** Everything about one plate that derives from its seed. */
private data class SeedLook(
    val gradient: Brush,
    val variant: Int,
    val motifSeed: Long,
    val motifInk: Color,
)

/**
 * Jewel tones anchored on the theme accent: the hue steps through five
 * seeded stops around the accent, then deepens so white ink always reads -
 * in both dark and light themes. The gradient angle steps with the seed so
 * neighbours in a list do not all lean the same way.
 */
private fun seedLook(key: String, accent: Color, ground: Color): SeedLook {
    val hueShift = (seedVariant(key, 5) - 2) * 24f
    val deep = accent.shiftHue(hueShift).darken(0.52f)
    val deeper = accent.shiftHue(hueShift + 18f).darken(0.68f)
    // A whisper of ground keeps the family tie in light themes without
    // washing the plate out.
    val end = lerp(deeper, ground, 0.18f)
    val angle = seedVariant(key, 4)
    val (from, to) = when (angle) {
        0 -> Offset(0f, 0f) to Offset(1f, 1f)
        1 -> Offset(1f, 0f) to Offset(0f, 1f)
        2 -> Offset(0.5f, 0f) to Offset(0.5f, 1f)
        else -> Offset(0f, 0.5f) to Offset(1f, 0.5f)
    }
    return SeedLook(
        gradient = Brush.linearGradient(listOf(deep, end), from, to),
        variant = seedVariant(key + "#motif", 4),
        motifSeed = key.hashCode().toLong(),
        motifInk = Color.White.copy(alpha = 0.16f),
    )
}

private fun DrawScope.drawMotif(variant: Int, seed: Long, ink: Color) {
    when (variant) {
        0 -> drawRings(seed, ink)
        1 -> drawBeams(seed, ink)
        2 -> drawDots(seed, ink)
        else -> drawArcs(seed, ink)
    }
}

/** Three concentric rings falling off the top-right corner. */
private fun DrawScope.drawRings(seed: Long, ink: Color) {
    val m = size.minDimension
    val dx = 0.72f + 0.04f * ((seed ushr 3) % 3)
    val center = Offset(size.width * dx, size.height * 0.22f)
    for (i in 1..3) {
        drawCircle(ink, m * (0.16f + 0.20f * i), center, style = Stroke(m * 0.05f))
    }
}

/** Three diagonal beams across the plate. */
private fun DrawScope.drawBeams(seed: Long, ink: Color) {
    val m = size.minDimension
    val tilt = -0.42f + 0.06f * ((seed ushr 5) % 5)
    val w = size.width
    val h = size.height
    for (i in 0..2) {
        val y = h * (0.30f + 0.20f * i)
        drawLine(
            ink,
            Offset(-w * 0.2f, y - w * tilt * 0.2f),
            Offset(w * 1.2f, y + w * tilt * 1.2f),
            strokeWidth = m * (0.07f + 0.03f * ((seed ushr (7 + i)) % 2)),
        )
    }
}

/** A fading dot grid in the lower-left. */
private fun DrawScope.drawDots(seed: Long, ink: Color) {
    val m = size.minDimension
    val step = m / 4.6f
    for (i in 0..4) {
        for (j in 0..4) {
            if (i + j > 5) continue
            val a = 0.05f + 0.05f * ((i * 7 + j * 13 + (seed and 7)) % 3)
            drawCircle(
                ink.copy(alpha = a.coerceIn(0.03f, 0.2f)),
                m * 0.032f,
                Offset(size.width * 0.10f + i * step, size.height * 0.92f - j * step),
            )
        }
    }
}

/** Two sweeping arcs from opposite corners. */
private fun DrawScope.drawArcs(seed: Long, ink: Color) {
    val m = size.minDimension
    val start = (seed % 360).toFloat()
    drawArc(
        ink,
        startAngle = start,
        sweepAngle = 130f,
        useCenter = false,
        topLeft = Offset(-m * 0.55f, -m * 0.55f),
        size = Size(m * 1.7f, m * 1.7f),
        style = Stroke(m * 0.055f),
    )
    drawArc(
        ink.copy(alpha = 0.10f),
        startAngle = start + 180f,
        sweepAngle = 100f,
        useCenter = false,
        topLeft = Offset(size.width - m * 1.15f, size.height - m * 1.15f),
        size = Size(m * 1.7f, m * 1.7f),
        style = Stroke(m * 0.04f),
    )
}

/** Hue rotation in HSV space; saturation and value survive the trip. */
private fun Color.shiftHue(degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[0] = ((hsv[0] + degrees) % 360f + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Scales the HSV value channel: 1 keeps the color, 0 is black. */
private fun Color.darken(keep: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[2] = (hsv[2] * keep).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor((alpha * 255).toInt(), hsv))
}
