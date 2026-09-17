package stream.kleeamp.mobile.data.visualizer

import kotlin.math.PI
import kotlin.math.sin

/**
 * The visualizer families the app can draw, and everything shared between the
 * places that actually render them: the in-app Compose meters and the
 * home-screen RemoteViews widget.
 *
 * A visualizer is described by an id (persisted in settings), the number of
 * columns it wants, and its brick geometry. Rendering itself is necessarily
 * different per host - Compose can draw a live 60fps canvas, Glance can only
 * paint a static snapshot - but the *values* fed to either renderer come from
 * the same [MeterCore] smoothing engine fed by the same analyser spectrum, so
 * the widget's bars and peaks are a true mirror of the in-app visualizer,
 * frozen at the last snapshot rather than a coarser stand-in.
 *
 * To add a new visualizer type later: add a [Visualizer] here, register its id
 * in the settings enum, and give it a Compose renderer and a widget painter
 * that both consume a [MeterCore] snapshot. The pipeline (AudioFx -> MeterCore
 * -> per-host renderer) is shared, so it works correctly everywhere for free.
 * [stream.kleeamp.mobile.widget.WidgetViz] is the widget-side dispatch that
 * maps the persisted setting id onto whichever painter draws it.
 */
enum class Visualizer(val id: String, val columns: Int, val brickDp: Int, val gapDp: Int) {
    /** The signature brick meter: 24 columns, the NowPlaying geometry. */
    Brick("spectrum", 24, 4, 3),

    /**
     * The widget's brick meter. Glance renders to RemoteViews, which hard-cap
     * every Row at 10 direct children, so the full 24-column meter cannot
     * render on a home screen. This compact variant draws the same smoothed
     * levels/peaks (downsampled from the 24-column MeterCore) at a column
     * count Glance can hold.
     */
    Widget("widget", 10, 4, 3),
    ;

    companion object {
        val default = Brick
        fun byId(id: String?): Visualizer =
            entries.firstOrNull { it.id == id } ?: default
    }
}

/**
 * The shared brick meter geometry. Both the in-app BrickMeter and the widget
 * painter read this so the pitch, gap and column count can never drift apart.
 */
object BrickGeometry {
    const val columnGapDp = 3
}

/**
 * Pure attack/release smoothing plus a lagging peak cap. This is the one
 * source of the lit levels and peak rows that every visualizer host draws,
 * so the in-app meter and the widget always agree.
 *
 * Each live spectrum frame is [push]ed (or [pushIdle] when there is no signal,
 * [settle] when nothing is playing) and the caller reads [levels]/[peaks] as
 * 0..1 per column. The in-app meter runs it at frame rate; the widget persists
 * a bounded-window copy of it for its static snapshot.
 */
class MeterCore(val columns: Int) {
    val levels = FloatArray(columns) { 0.05f }
    val peaks = FloatArray(columns) { 0.07f }

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
    }

    fun settle() {
        for (i in 0 until columns) {
            levels[i] = 0.04f
            peaks[i] = 0.06f
        }
    }

    fun snapshotLevels() = levels.copyOf()
    fun snapshotPeaks() = peaks.copyOf()
}
