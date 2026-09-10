package stream.cliamp.mobile.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import stream.cliamp.mobile.CliampApp
import stream.cliamp.mobile.MainActivity
import stream.cliamp.mobile.R
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.playback.PlaybackBus
import stream.cliamp.mobile.ui.clock
import stream.cliamp.mobile.ui.theme.CliampPalette
import stream.cliamp.mobile.ui.theme.paletteFor

const val WIDGET_ACTION_TOGGLE = "stream.cliamp.mobile.widget.TOGGLE"
const val WIDGET_ACTION_NEXT = "stream.cliamp.mobile.widget.NEXT"
const val WIDGET_ACTION_PREV = "stream.cliamp.mobile.widget.PREV"

/**
 * Classic RemoteViews widget backend, replacing Glance.
 *
 * Glance composes and inflates on every tap (Compose -> RemoteViews plus a
 * DataStore round-trip the UI had to wait on), which is where the multi-second
 * lag came from. This builds the same row straight into RemoteViews and pushes
 * it over binder: tap to pixels is a controller round-trip (~15ms) plus one
 * updateAppWidget (~20ms).
 *
 * Two entry points, both fire-and-forget:
 * - [push] carries in-memory state (station, live title, intent). The hot
 *   path: no disk read stands between the tap and the pixels.
 * - [refresh] re-reads DataStore. The cold path: boot, resize, theme change.
 *
 * DataStore is still written alongside (persistence for cold boot and the
 * tile fallback), but nothing on screen waits for it.
 *
 * Requests are trailing-edge conflated under [lock]: a tap fires play +
 * ready + ICY-title events within milliseconds, and they collapse into one
 * push of the latest row instead of concurrent pushes the launcher could
 * apply out of order.
 */
object WidgetRenderer {

    data class Row(
        val station: Station?,
        val track: String,
        val playing: Boolean,
        val seekable: Boolean = false,
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
    )

    /** Latest pushed row, for the tile's instant read. Null until first push. */
    @Volatile var lastKnown: Row? = null
        private set

    /** Below this width/height the centered compact card takes over. */
    private const val COMPACT_MAX_WIDTH_DP = 200
    private const val COMPACT_MAX_HEIGHT_DP = 84

    /** Scope flipbook: two frames a second of this bitmap over binder.
     * Painted at 2x and downscaled by the host for smooth edges. */
    private const val SCOPE_COLS = 32
    private const val SCOPE_WIDTH_PX = 508
    private const val SCOPE_HEIGHT_PX = 128
    private const val SCOPE_BRICK_PX = 8f
    private const val SCOPE_GAP_PX = 4f
    private const val SCOPE_COL_GAP_PX = 4f
    private const val SCOPE_RADIUS_PX = 2.5f
    /** Per-tick lerp toward the measured level: kills the 2Hz steppiness. */
    private const val SCOPE_SMOOTHING = 0.55f
    private const val SCOPE_PEAK_DECAY = 0.06f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    /** Guards the scope bitmap, its canvas and the peak memory: ticks and
     * full renders share one reusable bitmap. */
    private val scopeDrawLock = Any()
    private var scopeBitmap: Bitmap? = null
    private var scopeCanvas: Canvas? = null
    private val scopePeaks = FloatArray(SCOPE_COLS)
    private val scopeLevels = FloatArray(SCOPE_COLS)

    /** Palette of the last full render, for ticks that carry no theme. */
    @Volatile private var lastPalette: CliampPalette? = null

    /** True once the scope has been settled flat; skips repeat settle pushes. */
    @Volatile private var scopeSettled = false

    /** Last tick's clock second + duration: identical ticks are skipped. */
    @Volatile private var lastTickSecond = -1L
    @Volatile private var lastTickDuration = 0L

    private var appContext: Context? = null
    private var pending: Row? = null
    private var pendingCold = false
    private var inFlight = false
    private var again = false

    /** Hot path: render this exact row. Unknown progress fields keep the last
     * known values, so a play/pause flip never blanks the seek bar. */
    fun push(
        context: Context,
        station: Station?,
        track: String,
        playing: Boolean,
        seekable: Boolean? = null,
        durationMs: Long? = null,
        positionMs: Long? = null,
    ) {
        val prev = lastKnown
        val row = Row(
            station, track, playing,
            seekable ?: prev?.seekable ?: false,
            durationMs ?: prev?.durationMs ?: 0L,
            positionMs ?: prev?.positionMs ?: 0L,
        )
        lastKnown = row
        enqueue(context, row, cold = false)
    }

    /**
     * Progress tick: elapsed/total and the bar only, via a partial update so
     * the launcher merges a few views instead of re-inflating the row. Fired
     * once a second while a seekable source plays; everything else keeps
     * coming through [push].
     */
    fun pushProgress(context: Context, positionMs: Long, durationMs: Long) {
        val prev = lastKnown ?: return
        if (!prev.seekable || durationMs <= 0) return
        // The bar only moves once a second; identical ticks are skipped.
        val second = positionMs / 1000
        if (second == lastTickSecond && durationMs == lastTickDuration) return
        lastTickSecond = second
        lastTickDuration = durationMs
        lastKnown = prev.copy(positionMs = positionMs, durationMs = durationMs)
        val ctx = context.applicationContext
        scope.launch {
            val mgr = AppWidgetManager.getInstance(ctx)
            // The compact layout has no seek row; only full instances tick.
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CliampWidgetProvider::class.java))
                .filterNot { isCompact(mgr, it) }
            if (ids.isEmpty()) return@launch
            val rv = RemoteViews(ctx.packageName, R.layout.widget_cliamp)
            rv.setTextViewText(R.id.w_elapsed, clock(positionMs))
            rv.setTextViewText(R.id.w_total, "-" + clock((durationMs - positionMs).coerceAtLeast(0)))
            rv.setProgressBar(R.id.w_seekbar, durationMs.toInt(), positionMs.toInt().coerceIn(0, durationMs.toInt()), false)
            runCatching {
                for (id in ids) mgr.partiallyUpdateAppWidget(id, rv)
            }
        }
    }

    /** Cold path: re-read everything from DataStore. */
    fun refresh(context: Context) {
        enqueue(context, row = null, cold = true)
    }

    /**
     * Drops every bar to the grid: zeroed peaks plus one empty frame. Called
     * when playback stops or pauses, so the scope reads as silence instead
     * of a frozen mid-air frame. Skipped when already settled.
     */
    fun settleScope(context: Context) {
        if (scopeSettled) return
        val p = lastPalette ?: return
        scopeSettled = true
        val frame: Bitmap = synchronized(scopeDrawLock) {
            scopePeaks.fill(0f)
            scopeLevels.fill(0f)
            drawScope(FloatArray(0), p)
        }
        val ctx = context.applicationContext
        scope.launch {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CliampWidgetProvider::class.java))
                .filterNot { isCompact(mgr, it) }
            if (ids.isEmpty()) return@launch
            val rv = RemoteViews(ctx.packageName, R.layout.widget_cliamp)
            rv.setImageViewBitmap(R.id.w_scope, frame)
            runCatching {
                for (id in ids) mgr.partiallyUpdateAppWidget(id, rv)
            }
        }
    }

    /**
     * One scope frame: paints the latest FFT into the shared bitmap and
     * partially updates standard instances. No-ops without a rendered
     * palette or without spectrum - so the service can fire it on a dumb
     * cadence while playing and it costs nothing otherwise.
     */
    fun pushSpectrum(context: Context) {
        val spectrum = PlaybackBus.spectrum.value
        if (spectrum.isEmpty()) return
        val p = lastPalette ?: return
        val frame: Bitmap = synchronized(scopeDrawLock) {
            drawScope(spectrum, p)
        }
        scopeSettled = false
        val ctx = context.applicationContext
        scope.launch {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CliampWidgetProvider::class.java))
                .filterNot { isCompact(mgr, it) }
            if (ids.isEmpty()) return@launch
            val rv = RemoteViews(ctx.packageName, R.layout.widget_cliamp)
            rv.setImageViewBitmap(R.id.w_scope, frame)
            runCatching {
                for (id in ids) mgr.partiallyUpdateAppWidget(id, rv)
            }
        }
    }

    /**
     * The in-app brick meter as a bitmap: 32 columns folded from the 64 FFT
     * bands, bottom-anchored rounded bricks, unlit grid behind, a bright to
     * accent vertical sheen above the level, peak cap with decay. Levels are
     * lerped toward the measurement so the 2Hz flipbook glides instead of
     * stepping. Reuses one bitmap + canvas across ticks.
     */
    private fun drawScope(spectrum: FloatArray, p: CliampPalette): Bitmap {
        var bmp = scopeBitmap
        var canvas = scopeCanvas
        if (bmp == null || canvas == null || bmp.width != SCOPE_WIDTH_PX || bmp.height != SCOPE_HEIGHT_PX) {
            bmp = Bitmap.createBitmap(SCOPE_WIDTH_PX, SCOPE_HEIGHT_PX, Bitmap.Config.ARGB_8888)
            canvas = Canvas(bmp)
            scopeBitmap = bmp
            scopeCanvas = canvas
        }
        val unlit = Paint().apply { color = p.unlit.toArgb(); isAntiAlias = true }
        val lit = Paint().apply {
            isAntiAlias = true
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, SCOPE_HEIGHT_PX.toFloat(),
                p.accentBright.toArgb(), p.accent.toArgb(),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        val peak = Paint().apply { color = p.peak.toArgb(); isAntiAlias = true }
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)

        val step = SCOPE_BRICK_PX + SCOPE_GAP_PX
        val rows = ((SCOPE_HEIGHT_PX + SCOPE_GAP_PX) / step).toInt().coerceAtLeast(1)
        val colW = (SCOPE_WIDTH_PX - SCOPE_COL_GAP_PX * (SCOPE_COLS - 1)) / SCOPE_COLS
        val bandsPerCol = (spectrum.size / SCOPE_COLS).coerceAtLeast(1)
        for (c in 0 until SCOPE_COLS) {
            var measured = 0f
            for (b in 0 until bandsPerCol) {
                measured += spectrum.getOrElse(c * bandsPerCol + b) { 0f }
            }
            measured = (measured / bandsPerCol).coerceIn(0f, 1f)
            val level = scopeLevels[c] + (measured - scopeLevels[c]) * SCOPE_SMOOTHING
            scopeLevels[c] = level
            scopePeaks[c] = maxOf(level, scopePeaks[c] - SCOPE_PEAK_DECAY)
            val x = c * (colW + SCOPE_COL_GAP_PX)
            val litRows = (level * rows).toInt()
            for (r in 0 until rows) {
                val y = SCOPE_HEIGHT_PX - (r + 1) * step + SCOPE_GAP_PX
                canvas.drawRoundRect(
                    x, y, x + colW, y + SCOPE_BRICK_PX,
                    SCOPE_RADIUS_PX, SCOPE_RADIUS_PX,
                    if (r < litRows) lit else unlit,
                )
            }
            val pkRow = (scopePeaks[c].coerceIn(0f, 1f) * rows).toInt().coerceIn(0, rows - 1)
            val py = SCOPE_HEIGHT_PX - (pkRow + 1) * step + SCOPE_GAP_PX
            canvas.drawRoundRect(
                x, py, x + colW, py + SCOPE_BRICK_PX,
                SCOPE_RADIUS_PX, SCOPE_RADIUS_PX, peak,
            )
        }
        return bmp
    }

    private data class Req(val row: Row?, val cold: Boolean)

    private fun enqueue(context: Context, row: Row?, cold: Boolean) {
        synchronized(lock) {
            appContext = context.applicationContext
            // A hot row always wins; a cold re-read never clobbers one, since
            // the DataStore write it would read may still be in flight while
            // the row in hand is already the newest truth.
            if (row != null || pending == null) {
                pending = row
                pendingCold = cold && row == null
            } else if (cold) {
                pendingCold = true
            }
            if (inFlight) { again = true; return }
            inFlight = true
        }
        scope.launch {
            try {
                do {
                    synchronized(lock) { again = false }
                    // Let the trailing event land so this pass pushes the
                    // newest row, not the one mid-burst.
                    delay(100)
                    val req = synchronized(lock) { Req(pending, pendingCold) }
                    val ctx = synchronized(lock) { appContext } ?: return@launch
                    val t0 = SystemClock.uptimeMillis()
                    runCatching { render(ctx, req) }
                        .onFailure { Log.e("cliamp/wid", "widget push failed", it) }
                    Log.d("cliamp/wid", "widget push ms=${SystemClock.uptimeMillis() - t0}")
                } while (synchronized(lock) { again })
            } finally {
                // A request that slipped in after the last drain check set
                // [again] while [inFlight] was still true; re-enter so it is
                // not lost.
                val missed = synchronized(lock) { inFlight = false; again.also { again = false } }
                if (missed) enqueue(context, synchronized(lock) { pending }, synchronized(lock) { pendingCold })
            }
        }
    }

    private suspend fun render(ctx: Context, req: Req) {
        val app = ctx.applicationContext as CliampApp
        val row = req.row ?: Row(
            app.prefs.readLastStation(),
            app.prefs.widgetTrack.first(),
            app.prefs.widgetPlaying.first(),
            app.prefs.widgetSeekable.first(),
            app.prefs.widgetDuration.first(),
        )
        if (req.row == null) lastKnown = row
        val paletteName = app.prefs.palette.first()
        val systemDark = (ctx.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val p = paletteFor(paletteName, systemDark)

        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, CliampWidgetProvider::class.java))
        // Per instance: a tiny cell gets the centered compact card, anything
        // roomier the transport row with the flexing scope and seek.
        for (id in ids) {
            val (w, h) = cellSize(mgr, id)
            val compact = w < COMPACT_MAX_WIDTH_DP || h < COMPACT_MAX_HEIGHT_DP
            Log.d("cliamp/wid", "widget layout id=$id cell=${w}x${h} compact=$compact")
            mgr.updateAppWidget(id, buildViews(ctx, row, p, compact))
        }
        lastPalette = p
    }

    /**
     * True when the cell is too cramped for the full row (which wants ~250dp
     * of width for title + keys and ~90dp of height with the seek row). Sizes
     * come from the host in dp; the minimum across orientations wins so the
     * layout fits however the phone is held.
     */
    private fun isCompact(mgr: AppWidgetManager, id: Int): Boolean {
        val (w, h) = cellSize(mgr, id)
        return w < COMPACT_MAX_WIDTH_DP || h < COMPACT_MAX_HEIGHT_DP
    }

    private fun cellSize(mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
        val o = mgr.getAppWidgetOptions(id)
        val w = minOf(
            o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 999),
            o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 999),
        )
        val h = minOf(
            o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 999),
            o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 999),
        )
        return w to h
    }

    internal fun buildViews(
        ctx: Context,
        row: Row,
        p: CliampPalette,
        compact: Boolean = false,
    ): RemoteViews {
        val rv = RemoteViews(
            ctx.packageName,
            if (compact) R.layout.widget_cliamp_compact else R.layout.widget_cliamp,
        )
        rv.setTextViewText(R.id.w_title, row.station?.name ?: "nothing tuned")
        rv.setTextViewText(R.id.w_subtitle, widgetSubtitle(row.track, row.station))
        rv.setTextColor(R.id.w_title, p.ink.toArgb())
        rv.setTextColor(R.id.w_subtitle, p.inkTertiary.toArgb())

        rv.setInt(R.id.w_bg, "setColorFilter", p.ground.toArgb())
        rv.setInt(R.id.w_prev_bg, "setColorFilter", p.keyFace.toArgb())
        // The toggle is always filled (accent in dark, ink in light),
        // playing or paused - only its glyph swaps.
        rv.setInt(R.id.w_toggle_bg, "setColorFilter", filledKeyBg(p))
        rv.setInt(R.id.w_next_bg, "setColorFilter", p.keyFace.toArgb())
        rv.setInt(R.id.w_prev_icon, "setColorFilter", p.ink.toArgb())
        rv.setInt(R.id.w_next_icon, "setColorFilter", p.ink.toArgb())
        rv.setImageViewResource(
            R.id.w_toggle_icon,
            if (row.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play,
        )
        rv.setInt(R.id.w_toggle_icon, "setColorFilter", filledKeyFg(p))

        // Seekable sources (local files, provider tracks, episodes) get the
        // live position row - elapsed, bar, remaining - mirroring the
        // expanded player's scrubber readout. Live radio gets the streaming
        // rule instead. Neither shows before anything has played, and neither
        // exists in the compact layout, which has no room for a second row.
        val showSeek = !compact && row.seekable && row.durationMs > 0 && row.station != null
        rv.setViewVisibility(R.id.w_seek_row, if (showSeek) View.VISIBLE else View.GONE)
        rv.setViewVisibility(
            R.id.w_streaming,
            if (!compact && !showSeek && row.station != null) View.VISIBLE else View.GONE,
        )
        if (showSeek) {
            rv.setTextViewText(R.id.w_elapsed, clock(row.positionMs))
            rv.setTextViewText(
                R.id.w_total,
                "-" + clock((row.durationMs - row.positionMs).coerceAtLeast(0)),
            )
            rv.setTextColor(R.id.w_elapsed, p.inkSecondary.toArgb())
            rv.setTextColor(R.id.w_total, p.inkSecondary.toArgb())
            rv.setTextColor(R.id.w_streaming, p.inkTertiary.toArgb())
            rv.setProgressBar(
                R.id.w_seekbar,
                row.durationMs.toInt(),
                row.positionMs.toInt().coerceIn(0, row.durationMs.toInt()),
                false,
            )
            // Tint lists only exist on RemoteViews from API 31; older hosts
            // keep the static oxide fallbacks from the layout.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                rv.setColorStateList(
                    R.id.w_seekbar, "setProgressTintList",
                    ColorStateList.valueOf(p.accent.toArgb()),
                )
                rv.setColorStateList(
                    R.id.w_seekbar, "setProgressBackgroundTintList",
                    ColorStateList.valueOf(p.track.toArgb()),
                )
            }
        }

        // The scope lives in the standard layout only and shows whenever
        // something is tuned, flexing to the leftover height: a slim strip
        // in a one-row cell, tall bricks in a two-row one. Its bitmap
        // arrives separately (pushSpectrum flipbook); the current frame is
        // painted inline here so a full re-render never blanks it. Paused
        // renders silence, never the frozen live frame.
        val showScope = !compact && row.station != null
        if (!compact) {
            rv.setViewVisibility(R.id.w_scope, if (showScope) View.VISIBLE else View.GONE)
            if (showScope) {
                val frame = synchronized(scopeDrawLock) {
                    if (!row.playing) {
                        scopePeaks.fill(0f)
                        scopeLevels.fill(0f)
                    }
                    drawScope(if (row.playing) PlaybackBus.spectrum.value else FloatArray(0), p)
                }
                scopeSettled = !row.playing
                rv.setImageViewBitmap(R.id.w_scope, frame)
            }
        }
        rv.setOnClickPendingIntent(R.id.w_toggle, actionIntent(ctx, WIDGET_ACTION_TOGGLE, 1))
        rv.setOnClickPendingIntent(R.id.w_prev, actionIntent(ctx, WIDGET_ACTION_PREV, 2))
        rv.setOnClickPendingIntent(R.id.w_next, actionIntent(ctx, WIDGET_ACTION_NEXT, 3))
        rv.setOnClickPendingIntent(
            R.id.w_content,
            PendingIntent.getActivity(
                ctx, 4,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        return rv
    }

    private fun filledKeyBg(p: CliampPalette) =
        if (p.dark) p.accent.toArgb() else p.ink.toArgb()

    private fun filledKeyFg(p: CliampPalette) =
        if (p.dark) p.onAccent.toArgb() else p.ground.toArgb()

    private fun actionIntent(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, CliampWidgetProvider::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * The artist line beneath the station name, mirroring the in-app player:
     * the live stream title (ICY metadata) wins when present, otherwise the
     * station's own metadata per source.
     */
    private fun widgetSubtitle(track: String, station: Station?): String {
        if (track.isNotBlank()) return track
        return when (station?.source) {
            StationSource.Cliamp -> "cliamp radio"
            StationSource.Local -> station.artistAlbum.ifBlank { "local audio" }
            StationSource.Podcast -> station.artist.ifBlank { "podcast" }
            StationSource.Provider -> station.meta.ifBlank { "live stream" }
            else -> station?.meta?.ifBlank { "live stream" }.orEmpty().ifBlank { "pick a station" }
        }
    }
}
