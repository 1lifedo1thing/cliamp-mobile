package stream.cliamp.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import stream.cliamp.mobile.ui.theme.LocalPalette
import kotlin.math.PI
import kotlin.math.sin

/**
 * The signature element. Each column is three layers, all anchored to the
 * bottom so brick phase never shifts as the level animates:
 *
 *  1. unlit grid, full height
 *  2. lit grid, height = level
 *  3. peak cap, one brick tall, floating above and lagging behind
 *
 * Bricks are laid out from the bottom edge upward, which is what keeps the
 * grid phase stable - drawing top-down would make the whole stack shimmer.
 */
@Composable
fun BrickMeter(
    frame: MeterFrame,
    modifier: Modifier = Modifier,
    brick: Dp = 4.dp,
    gap: Dp = 3.dp,
    columnGap: Dp = 3.dp,
    litColor: Color = LocalPalette.current.accent,
    unlitColor: Color = LocalPalette.current.unlit,
    peakColor: Color = LocalPalette.current.peak,
    showPeaks: Boolean = true,
) {
    Canvas(modifier) {
        // reading the counter inside draw is what re-runs this on each frame
        @Suppress("UNUSED_EXPRESSION") frame.frame

        val n = frame.columns
        if (n == 0) return@Canvas
        val brickPx = brick.toPx()
        val gapPx = gap.toPx()
        val colGapPx = columnGap.toPx()
        val step = brickPx + gapPx
        val colW = (size.width - colGapPx * (n - 1)) / n
        if (colW <= 0f) return@Canvas
        val rows = ((size.height + gapPx) / step).toInt().coerceAtLeast(1)

        for (c in 0 until n) {
            val x = c * (colW + colGapPx)
            val litRows = (frame.levels[c].coerceIn(0f, 1f) * rows).toInt()
            for (r in 0 until rows) {
                val y = size.height - (r + 1) * step + gapPx
                drawRect(
                    color = if (r < litRows) litColor else unlitColor,
                    topLeft = Offset(x, y),
                    size = Size(colW, brickPx),
                )
            }
            if (showPeaks) {
                val pkRow = (frame.peaks[c].coerceIn(0f, 1f) * rows).toInt().coerceIn(0, rows - 1)
                drawRect(
                    color = peakColor,
                    topLeft = Offset(x, size.height - (pkRow + 1) * step + gapPx),
                    size = Size(colW, brickPx),
                )
            }
        }
    }
}

/**
 * Static variant for data that is not a live signal - the 31-day session
 * history on the stats screen reuses the same grid so the app has one way of
 * drawing a series, not two.
 */
@Composable
fun BrickBars(
    values: List<Float>,
    modifier: Modifier = Modifier,
    brick: Dp = 4.dp,
    gap: Dp = 3.dp,
    columnGap: Dp = 3.dp,
    litColor: Color = LocalPalette.current.accent,
    unlitColor: Color = LocalPalette.current.unlit,
) {
    Canvas(modifier) {
        val n = values.size
        if (n == 0) return@Canvas
        val brickPx = brick.toPx()
        val gapPx = gap.toPx()
        val colGapPx = columnGap.toPx()
        val step = brickPx + gapPx
        val colW = (size.width - colGapPx * (n - 1)) / n
        if (colW <= 0f) return@Canvas
        val rows = ((size.height + gapPx) / step).toInt().coerceAtLeast(1)
        for (c in 0 until n) {
            val x = c * (colW + colGapPx)
            val litRows = (values[c].coerceIn(0f, 1f) * rows).toInt()
            for (r in 0 until rows) {
                drawRect(
                    color = if (r < litRows) litColor else unlitColor,
                    topLeft = Offset(x, size.height - (r + 1) * step + gapPx),
                    size = Size(colW, brickPx),
                )
            }
        }
    }
}

/** Column count/geometry presets, straight from the concept. */
enum class MeterSize(val columns: Int, val brick: Dp, val gap: Dp, val height: Dp) {
    NowPlaying(24, 4.dp, 3.dp, 66.dp),
    Scope(32, 6.dp, 4.dp, 200.dp),
    Lockscreen(28, 3.dp, 3.dp, 40.dp),
    Mini(14, 3.dp, 2.dp, 22.dp),
}

/**
 * One meter state source, so the call site never branches between two
 * different `remember` trees - conditional remembers would drop and rebuild
 * the frame loop every time playback started or stopped.
 *
 * When a real spectrum is available it is used directly; otherwise the columns
 * fall back to a synthesised idle animation. Per-column variety comes from
 * staggered period and phase, never from randomised colour.
 */
@Composable
fun rememberMeter(
    columns: Int,
    live: Boolean,
    spectrum: State<FloatArray>? = null,
): MeterFrame {
    val frame = remember(columns) { MeterFrame(columns) }
    val src = spectrum?.value
    val useReal = live && src != null && src.isNotEmpty()

    LaunchedEffect(columns, live, useReal) {
        if (!live) {
            frame.settle()
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val real = spectrum?.value
                if (real != null && real.isNotEmpty()) {
                    frame.push(real)
                } else {
                    val t = (now - start) / 1_000_000_000.0
                    frame.pushIdle(t)
                }
            }
        }
    }
    return frame
}

/**
 * Holds the lit levels and the lagging peak caps. Both arrays are mutated in
 * place and read inside a Canvas draw, so the frame counter is what drives
 * recomposition rather than the arrays themselves.
 */
@Stable
class MeterFrame(val columns: Int) {
    val levels = FloatArray(columns) { 0.05f }
    val peaks = FloatArray(columns) { 0.07f }
    var frame by mutableIntStateOf(0)
        private set

    private val attack = 0.55f
    private val release = 0.14f
    private val peakFall = 0.010f

    /**
     * The analyser publishes a fixed number of bands; each meter asks for its
     * own column count. Pooling here (rather than requiring an exact match)
     * is what stops a 24-column meter silently falling back to the fake
     * animation while a 32-column one shows the real thing.
     */
    fun push(source: FloatArray) {
        if (source.isEmpty()) return
        for (i in 0 until columns) {
            val t = bandFor(source, i).coerceIn(0f, 1f)
            val k = if (t > levels[i]) attack else release
            levels[i] += (t - levels[i]) * k
            peaks[i] = if (levels[i] >= peaks[i]) levels[i]
            else (peaks[i] - peakFall).coerceAtLeast(levels[i])
        }
        frame++
    }

    private fun bandFor(src: FloatArray, i: Int): Float {
        if (src.size == columns) return src[i]
        if (src.size < columns) {
            // upsample: nearest band, no invented detail
            return src[(i.toLong() * src.size / columns).toInt().coerceIn(0, src.lastIndex)]
        }
        val lo = (i.toLong() * src.size / columns).toInt()
        val hi = ((i + 1).toLong() * src.size / columns).toInt().coerceAtLeast(lo + 1)
        var peak = 0f
        for (k in lo until hi.coerceAtMost(src.size)) if (src[k] > peak) peak = src[k]
        return peak
    }

    fun pushIdle(t: Double) {
        for (i in 0 until columns) {
            val period = 0.85 + (i % 7) * 0.11
            val phase = (i % 6) * 0.07
            val s = (sin(2 * PI * ((t / period) + phase)) + 1.0) / 2.0
            val bias = 0.34 + 0.5 * ((i * 37 % 13) / 13.0)
            levels[i] = (0.12 + s * bias).toFloat().coerceIn(0f, 0.96f)
            peaks[i] = (levels[i] + 0.08f).coerceIn(0f, 0.99f)
        }
        frame++
    }

    fun settle() {
        for (i in 0 until columns) {
            levels[i] = 0.04f
            peaks[i] = 0.06f
        }
        frame++
    }
}
