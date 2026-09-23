package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import stream.kleeamp.mobile.theme.KleeampPalette
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Poppins
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The kleeamp mark geometry, in its own 48-unit view box: x, width, half height. */
private val MARK_BARS = listOf(
    floatArrayOf(5f, 5f, 4f),
    floatArrayOf(12f, 5f, 12f),
    floatArrayOf(19f, 5f, 20f),
    floatArrayOf(26f, 5f, 10f),
    floatArrayOf(33f, 5f, 6f),
    floatArrayOf(40f, 3f, 2f),
)
private const val MARK_VIEW = 48f
private const val MARK_CENTER = 24f

/**
 * The kleeamp field: the brand mark becomes the equalizer. Six bars pulse
 * symmetrically about the horizon, the thin spectrum mirrors around them, and
 * every bass hit fires a shockwave, a spark shower, a flash and a shake.
 */
@Composable
internal fun VisKleeamp(frame: KleeampFrame, modifier: Modifier) {
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer(cacheSize = 4)
    Canvas(modifier.clipToBounds()) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val core = frame.core
        val burst = core.burst

        val cx = size.width / 2f
        val midY = size.height * 0.40f
        val markBox = min(size.height * 0.66f, size.width * 0.60f)
        val scale = markBox / MARK_VIEW
        val unit = max(1f, size.height / 44f)

        val shakeX = sin(frame.frame * 0.9f) * burst.shake * unit * 2.4f
        val shakeY = cos(frame.frame * 1.3f) * burst.shake * unit * 1.7f

        translate(shakeX, shakeY) {
            drawField(p, core, midY, unit)
            drawGlow(p, core, burst.flash, cx, midY, markBox)
            drawMark(p, core, cx, midY, scale)
            drawWordmark(p, core, measurer, cx, midY, scale, burst.flash)
            drawShock(p, burst, cx, midY, unit)
            drawSparks(p, burst, unit)
        }

        if (burst.flash > 0.01f) {
            drawRect(
                color = p.accentBright.copy(alpha = 0.04f + 0.10f * burst.flash),
                blendMode = BlendMode.Plus,
            )
        }
    }
}

private fun DrawScope.drawField(p: KleeampPalette, core: KleeampCore, midY: Float, unit: Float) {
    val n = core.columns
    if (n == 0) return
    val gap = max(1f, unit * 0.9f)
    val colW = (size.width - gap * (n - 1)) / n
    if (colW <= 0f) return
    val reach = midY - size.height * 0.07f
    if (reach <= 0f) return

    drawRect(
        color = p.unlit.copy(alpha = 0.30f),
        topLeft = Offset(0f, midY - reach),
        size = Size(size.width, reach * 2f),
    )

    val cap = max(1f, unit * 0.9f)
    for (c in 0 until n) {
        val level = core.levels[c].coerceIn(0f, 1f)
        val half = level * reach
        if (half < 1f) continue
        val x = c * (colW + gap)
        drawRect(
            color = visTier(p, VisMath.tier(level)),
            topLeft = Offset(x, midY - half),
            size = Size(colW, half * 2f),
        )
        drawRect(p.accentBright, Offset(x, midY - half - cap / 2f), Size(colW, cap))
        drawRect(p.accentBright, Offset(x, midY + half - cap / 2f), Size(colW, cap))
    }
}

private fun DrawScope.drawGlow(
    p: KleeampPalette,
    core: KleeampCore,
    flash: Float,
    cx: Float,
    midY: Float,
    markBox: Float,
) {
    val radius = markBox * (0.85f + 0.45f * core.bass + 0.5f * flash)
    if (radius <= 0f) return
    val alpha = (0.05f + 0.20f * core.bass + 0.30f * flash).coerceIn(0f, 0.5f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(p.accentBright.copy(alpha = alpha), Color.Transparent),
            center = Offset(cx, midY),
            radius = radius,
        ),
        radius = radius,
        center = Offset(cx, midY),
    )
}

private fun DrawScope.drawMark(p: KleeampPalette, core: KleeampCore, cx: Float, midY: Float, scale: Float) {
    for (i in MARK_BARS.indices) {
        val bar = MARK_BARS[i]
        val level = core.barLevels.getOrElse(i) { 0f }.coerceIn(0f, 1f)
        val live = 0.35f + 0.65f * level
        val half = bar[2] * live * scale
        val x = cx + (bar[0] - MARK_CENTER) * scale
        drawRect(
            color = p.peak,
            topLeft = Offset(x, midY - half),
            size = Size(bar[1] * scale, half * 2f),
        )
    }
}

private fun DrawScope.drawWordmark(
    p: KleeampPalette,
    core: KleeampCore,
    measurer: TextMeasurer,
    cx: Float,
    midY: Float,
    scale: Float,
    flash: Float,
) {
    if (size.height < 96.dp.toPx()) return
    // The wordmark: Poppins ExtraBold, lowercase, −0.035em. Sized to the frame.
    val base = TextStyle(
        fontFamily = Poppins,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.035).em,
    )
    var fontSizePx = size.height * 0.15f
    var layout = measurer.measure("kleeamp", base.copy(fontSize = fontSizePx.toSp()))
    val maxWidth = size.width * 0.72f
    if (layout.size.width > maxWidth) {
        fontSizePx *= maxWidth / layout.size.width
        layout = measurer.measure("kleeamp", base.copy(fontSize = fontSizePx.toSp()))
    }
    val y = midY + 20f * scale + size.height * 0.03f
    if (y + layout.size.height > size.height) return
    val alpha = (0.60f + 0.28f * core.bass + 0.12f * flash).coerceIn(0f, 1f)
    drawText(
        textLayoutResult = layout,
        color = p.peak.copy(alpha = alpha),
        topLeft = Offset(cx - layout.size.width / 2f, y),
    )
}

private fun DrawScope.drawShock(p: KleeampPalette, burst: BurstCore, cx: Float, midY: Float, unit: Float) {
    if (burst.shockLife <= 0f) return
    val reach = max(size.width, size.height) * 0.92f
    val radius = burst.shockRadius * reach
    drawCircle(
        color = p.accentBright.copy(alpha = (0.55f * burst.shockLife).coerceIn(0f, 0.55f)),
        radius = radius,
        center = Offset(cx, midY),
        style = Stroke(width = unit * 2.2f),
    )
    drawCircle(
        color = p.accent.copy(alpha = 0.28f * burst.shockLife),
        radius = radius * 0.72f,
        center = Offset(cx, midY),
        style = Stroke(width = unit * 1.3f),
    )
}

private fun DrawScope.drawSparks(p: KleeampPalette, burst: BurstCore, unit: Float) {
    for (spark in burst.sparks) {
        val life = (spark.life / spark.maxLife).coerceIn(0f, 1f)
        val head = Offset(spark.x * size.width, spark.y * size.height)
        val tail = Offset(spark.px * size.width, spark.py * size.height)
        drawLine(
            color = p.accent.copy(alpha = 0.18f + 0.42f * life),
            start = tail,
            end = head,
            strokeWidth = unit * 1.2f,
        )
        val sparkSize = unit * (0.8f + 1.2f * life)
        drawRect(
            color = p.peak.copy(alpha = 0.45f + 0.55f * life),
            topLeft = Offset(head.x - sparkSize / 2f, head.y - sparkSize / 2f),
            size = Size(sparkSize, sparkSize),
        )
    }
}
