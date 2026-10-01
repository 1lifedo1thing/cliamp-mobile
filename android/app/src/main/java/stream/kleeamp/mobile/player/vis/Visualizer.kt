package stream.kleeamp.mobile.player.vis

import kotlin.math.exp
import stream.kleeamp.mobile.model.NowPlaying

/**
 * The visualizer families the app can draw, shared by the in-app Compose
 * meters (the home-screen widget renders no visualizer).
 *
 * A visualizer is described by an id (persisted in settings), the number of
 * columns it wants, and its brick geometry. Rendering is a live 60fps Compose
 * canvas fed by the same [MeterCore] smoothing engine and the same analyser
 * spectrum.
 *
 * To add a new visualizer type later: add a [Visualizer] here, register its id
 * in the settings enum, and give it a Compose renderer that consumes a
 * [MeterCore] snapshot.
 */
enum class Visualizer(val id: String, val label: String, val columns: Int) {
    /** The signature brick meter: 24 columns, the NowPlaying geometry. */
    Brick("spectrum", "spectrum", 24),

    /** The oscilloscope: raw time-domain trace, ported from cliamp's wave. */
    Wave("wave", "wave", 24),

    Bars("bars", "bars", 24),

    ClassicPeak("classicpeak", "classic peak", 28),

    Matrix("matrix", "matrix", 32),

    Butterfly("butterfly", "butterfly", 24),

    ClassicLed("classicled", "classic led", 28),

    Stereo("stereo", "stereo", 28),

    Omarchy("omarchy", "omarchy", 24),

    Kleeamp("kleeamp", "kleeamp", 24),

    Rain("rain", "rain", 24),

    BarsDot("barsdot", "bars dot", 24),

    BarsOutline("barsoutline", "bars outline", 24),

    Bricks("bricks", "bricks", 24),

    Columns("columns", "columns", 24),

    Pulse("pulse", "pulse", 24),

    Retro("retro", "retro", 24),

    Mirror("mirror", "mirror", 24),

    Scatter("scatter", "scatter", 32),

    Flame("flame", "flame", 24),

    Sakura("sakura", "sakura", 24),

    Firework("firework", "firework", 24),

    Bubbles("bubbles", "bubbles", 24),

    Sand("sand", "sand", 24),

    Geyser("geyser", "geyser", 24),

    Firefly("firefly", "firefly", 24),

    Binary("binary", "binary", 24),

    Logo("logo", "logo", 24),

    Terrain("terrain", "terrain", 32),

    Scope("scope", "scope", 24),

    Heartbeat("heartbeat", "heartbeat", 24),

    Ascii("ascii", "ascii", 28),

    Mosaic("mosaic", "mosaic", 24),

    RedSector("redsector", "red sector", 24),

    /**
     * The widget's brick meter. Glance renders to RemoteViews, which hard-cap
     * every Row at 10 direct children, so the full 24-column meter cannot
     * render on a home screen. This compact variant draws the same smoothed
     * levels/peaks (downsampled from the 24-column MeterCore) at a column
     * count Glance can hold.
     */
    Widget("widget", "widget", 10),
    ;

    companion object {
        val default = Brick

        val selectable: List<Visualizer> get() = entries.filter { it != Widget }

        fun byId(id: String?): Visualizer =
            entries.firstOrNull { it.id == id } ?: default
    }
}

/**
 * Pure attack/release smoothing plus a lagging peak cap. This is the one
 * source of the lit levels and peak rows that every visualizer host draws,
 * so the in-app meter and the widget always agree.
 *
 * Each live spectrum frame from [AudioAnalyzer] is [push]ed; [settle] parks
 * the meter at rest when nothing is playing. There is deliberately no
 * synthetic idle animation: with no PCM the meter shows silence, never a
 * sine dance — silence in means silence out.
 */
class MeterCore(val columns: Int) {
    val levels = FloatArray(columns) { 0.05f }
    val peaks = FloatArray(columns) { 0.07f }

    private val peakFall = 0.010f

    /** cliamp's classicPeakStep rates: fast attack, slow decay. */
    private val riseRate = 34f
    private val fallRate = 10f

    /**
     * The analyser publishes a fixed number of bands; each meter asks for its
     * own column count. Pooling here (rather than requiring an exact match)
     * is what stops a 24-column meter silently falling back to the fake
     * animation while a 32-column one shows the real thing.
     *
     * Smoothing uses cliamp's attack/release rates (34/s up, 10/s down) with
     * the real frame dt, so bars track transients identically at 60 and 120
     * Hz. Fixed per-frame fractions would double the speed on 120 Hz screens.
     */
    fun push(source: FloatArray, dt: Float) {
        if (source.isEmpty()) return
        val step = dt.coerceIn(0f, 0.1f)
        for (i in 0 until columns) {
            val t = bandFor(source, i).coerceIn(0f, 1f)
            val rate = if (t > levels[i]) riseRate else fallRate
            levels[i] += (t - levels[i]) * (1f - exp(-rate * step))
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

    fun settle() {
        for (i in 0 until columns) {
            levels[i] = 0.04f
            peaks[i] = 0.06f
        }
    }

    fun snapshotLevels() = levels.copyOf()
    fun snapshotPeaks() = peaks.copyOf()
}
