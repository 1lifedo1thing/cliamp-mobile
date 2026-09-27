package stream.kleeamp.mobile.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import stream.kleeamp.mobile.R
import stream.kleeamp.mobile.art.initialOf
import stream.kleeamp.mobile.art.seedVariant
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import java.io.ByteArrayOutputStream

/**
 * Radio has no cover art, and the directory's favicons are 32px JPEGs of wildly
 * varying quality. Rather than ship a blank square, we draw the same seeded
 * plate the app shows for a coverless item: a jewel-tone gradient, the seeded
 * geometric motif and the item's monogram.
 *
 * This matters more than it looks: Android's media player derives the whole
 * chip's background and accent colours from the artwork, so with no artwork the
 * notification is grey system chrome, and with this it picks up the plate's.
 * The gradient is anchored on the theme's accent, pushed in from the UI layer
 * so the shade wears the exact colours the in-app plate does - a station with
 * no art of its own still reads as the app seen from outside. Setting
 * a colour on the notification is not enough on its own: One UI reads the
 * artwork and ignores it.
 *
 * Anything that has real cover art overrides all of that and fills the square
 * edge to edge, so the chip takes the cover's own colours. Two shapes of art
 * arrive here and they cannot be treated alike: a podcast, album or local
 * track carries a square cover that should be cropped to fill, while a radio
 * station's branding is an og:image, typically a 1200x630 wordmark that a
 * centre crop would guillotine, so that stays letterboxed on the plate.
 */
object StationArtwork {

    private const val SIZE = 512
    private val cache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 12
    }

    /**
     * Every finite track (local / provider / podcast) renders the same generic
     * no-art plate - its real cover, when there is one, is applied separately via
     * [withArt] once it loads. So the plate is a pure constant for all tracks, and
     * rendering it once and reusing the bytes across the whole queue turns a
     * 60-track window rebuild (which previously re-rendered and re-compressed a
     * full 512px PNG per item, ~1s of work on every tap) into a constant-time
     * lookup. Rendered lazily on first use, off the read path.
     */
    private var genericTrackPlate: ByteArray? = null

    /**
     * The plate's hue anchor and ground whisper, pushed in from the UI layer
     * whenever the palette resolves: the render path is synchronous (and the
     * preference store is not), so the activity hands the current palette's
     * accent and ground over instead. Defaults are oxide, the pre-theme
     * behaviour. A change clears the caches - rendered bytes bake the colours
     * in, so last theme's plates must not survive a switch.
     */
    @Volatile
    private var plateAccent: Int = Color.parseColor(ACCENT)

    @Volatile
    private var plateGround: Int = Color.parseColor(GROUND)

    fun setPlateColors(accent: ComposeColor, ground: ComposeColor) {
        val a = accent.toArgb()
        val g = ground.toArgb()
        if (a == plateAccent && g == plateGround) return
        synchronized(cache) {
            plateAccent = a
            plateGround = g
            cache.clear()
            genericTrackPlate = null
        }
    }

    /** Fast path: no network, drawn locally, safe to call before playback starts. */
    fun forStation(context: Context, station: Station): ByteArray? = synchronized(cache) {
        // Only share the plate across tracks that render the literal generic
        // caption; a track that somehow carries a slug or country code would
        // draw a distinct caption and must keep its own art.
        if (station.isTrack && station.slug.isBlank() && station.countryCode.isBlank()) {
            genericTrackPlate?.let { return@synchronized it }
            return@synchronized render(context, station, null).also { genericTrackPlate = it }
        }
        cache.getOrPut(station.id) { render(context, station, null) }
    }

    /**
     * Slow path: real art, once it has arrived over the network. A square cover
     * fills the square; a station's wide branding is letterboxed onto the plate.
     */
    fun withArt(context: Context, station: Station, art: Bitmap): ByteArray =
        render(context, station, art)

    /**
     * Whether [station]'s art is a square cover rather than a wide wordmark.
     *
     * This is the same set of sources as [Station.isTrack], and deliberately
     * not that property: they coincide today because everything finite happens
     * to ship square artwork, but one is about how the queue advances and this
     * is about how a bitmap is cropped.
     */
    private fun Station.hasSquareCover(): Boolean =
        source == StationSource.Local ||
            source == StationSource.Provider ||
            source == StationSource.Podcast

    private inline fun <K, V> LinkedHashMap<K, V>.getOrPut(key: K, produce: () -> V): V =
        get(key) ?: produce().also { put(key, it) }

    // Single artwork render pipeline from resolve to encode.
    @Suppress("LongMethod")
    private fun render(context: Context, station: Station, art: Bitmap?): ByteArray {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Seeded jewel-tone ground, the same look the in-app plate wears:
        // the hue steps around the fixed oxide accent, then deepens so the
        // monogram always reads. Same seed key as the in-app plate, so the
        // motif variant, angle and monogram match it.
        val seedKey = station.id.ifBlank { station.url }
        val hueShift = (seedVariant(seedKey, 5) - 2) * 24f
        val deep = darken(shiftHue(plateAccent, hueShift), 0.52f)
        val deeper = darken(shiftHue(plateAccent, hueShift + 18f), 0.68f)
        val end = lerpColor(deeper, plateGround, 0.18f)
        val angle = seedVariant(seedKey, 4)
        val f = SIZE.toFloat()
        val gx = when (angle) {
            0 -> floatArrayOf(0f, 0f, f, f)
            1 -> floatArrayOf(f, 0f, 0f, f)
            2 -> floatArrayOf(f / 2f, 0f, f / 2f, f)
            else -> floatArrayOf(0f, f / 2f, f, f / 2f)
        }
        paint.shader = LinearGradient(gx[0], gx[1], gx[2], gx[3], deep, end, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, f, f, paint)
        paint.shader = null

        if (art != null) {
            if (station.hasSquareCover()) {
                // Edge to edge, centre-cropped: nothing of the plate survives,
                // which is the point - the chip should read as the cover.
                val k = maxOf(SIZE / art.width.toFloat(), SIZE / art.height.toFloat())
                val w = art.width * k
                val h = art.height * k
                c.drawBitmap(
                    art,
                    null,
                    android.graphics.RectF(
                        (SIZE - w) / 2f, (SIZE - h) / 2f,
                        (SIZE + w) / 2f, (SIZE + h) / 2f,
                    ),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
            } else {
                val inset = 34f
                val box = SIZE - inset * 2
                val k = minOf(box / art.width, box / art.height)
                val w = art.width * k
                val h = art.height * k
                val dst = android.graphics.RectF(
                    (SIZE - w) / 2f, (SIZE - h) / 2f,
                    (SIZE + w) / 2f, (SIZE + h) / 2f,
                )
                c.drawBitmap(art, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = Color.parseColor(FRAME)
                c.drawRect(1f, 1f, SIZE - 1f, SIZE - 1f, paint)
            }
            return ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                bmp.recycle()
                out.toByteArray()
            }
        }

        // Seeded motif + monogram, the same geometry the in-app plate
        // draws. The media player crops this square to a wide chip, so the
        // monogram sits centred to survive that crop whole.
        drawMotif(c, seedVariant(seedKey + "#motif", 4), seedKey.hashCode().toLong())
        initialOf(station.name)?.let { glyph ->
            val font = runCatching { ResourcesCompat.getFont(context, R.font.poppins_regular) }
                .getOrNull() ?: Typeface.DEFAULT
            paint.typeface = font
            paint.textSize = SIZE * 0.44f
            paint.textAlign = Paint.Align.CENTER
            paint.color = Color.argb(235, 255, 255, 255)
            val fm = paint.fontMetrics
            c.drawText(glyph, f / 2f, f / 2f - (fm.ascent + fm.descent) / 2f, paint)
            paint.textAlign = Paint.Align.LEFT
        }

        // caption, matching the player's "[ slug - cliamp radio ]"
        val font = runCatching { ResourcesCompat.getFont(context, R.font.poppins_regular) }
            .getOrNull() ?: Typeface.DEFAULT
        paint.typeface = font
        paint.textSize = 26f
        paint.color = Color.parseColor(CAPTION)
        val caption = when {
            station.source == StationSource.Cliamp -> "[ ${station.slug} · cliamp radio ]"
            station.countryCode.isNotBlank() -> "[ ${station.countryCode.lowercase()} · live stream ]"
            else -> "[ live stream ]"
        }
        c.drawText(caption.take(34), 34f, SIZE - 40f, paint)

        // hairline frame
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.parseColor(FRAME)
        c.drawRect(1f, 1f, SIZE - 1f, SIZE - 1f, paint)

        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    /**
     * The seeded motif, ported from the in-app plate's drawMotif: rings off
     * the top-right corner, diagonal beams, a fading dot grid, or sweeping
     * arcs. White at the same 0.16 alpha the plate uses.
     */
    private fun drawMotif(c: Canvas, variant: Int, seed: Long) {
        val f = SIZE.toFloat()
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(41, 255, 255, 255)
        }
        when (variant) {
            0 -> {
                val dx = 0.72f + 0.04f * ((seed ushr 3) % 3)
                val cx = f * dx
                val cy = f * 0.22f
                for (i in 1..3) {
                    ink.strokeWidth = f * 0.05f
                    c.drawCircle(cx, cy, f * (0.16f + 0.20f * i), ink)
                }
            }
            1 -> {
                val tilt = -0.42f + 0.06f * ((seed ushr 5) % 5)
                for (i in 0..2) {
                    val y = f * (0.30f + 0.20f * i)
                    ink.strokeWidth = f * (0.07f + 0.03f * ((seed ushr (7 + i)) % 2))
                    c.drawLine(-f * 0.2f, y - f * tilt * 0.2f, f * 1.2f, y + f * tilt * 1.2f, ink)
                }
            }
            2 -> {
                ink.style = Paint.Style.FILL
                val step = f / 4.6f
                for (i in 0..4) {
                    for (j in 0..4) {
                        if (i + j > 5) continue
                        val a = (0.05f + 0.05f * ((i * 7 + j * 13 + (seed and 7)) % 3))
                            .coerceIn(0.03f, 0.2f)
                        ink.color = Color.argb((a * 255).toInt(), 255, 255, 255)
                        c.drawCircle(f * 0.10f + i * step, f * 0.92f - j * step, f * 0.032f, ink)
                    }
                }
            }
            else -> {
                val start = (seed % 360).toFloat()
                ink.strokeWidth = f * 0.055f
                c.drawArc(
                    android.graphics.RectF(-f * 0.55f, -f * 0.55f, f * 1.15f, f * 1.15f),
                    start, 130f, false, ink,
                )
                ink.strokeWidth = f * 0.04f
                ink.color = Color.argb(26, 255, 255, 255)
                c.drawArc(
                    android.graphics.RectF(f - f * 1.15f, f - f * 1.15f, f + f * 0.55f, f + f * 0.55f),
                    start + 180f, 100f, false, ink,
                )
            }
        }
    }

    /** Hue rotation in HSV space; saturation and value survive the trip. */
    private fun shiftHue(color: Int, degrees: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[0] = ((hsv[0] + degrees) % 360f + 360f) % 360f
        return Color.HSVToColor(hsv)
    }

    /** Scales the HSV value channel: 1 keeps the color, 0 is black. */
    private fun darken(color: Int, keep: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * keep).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    /** Straight ARGB lerp; [t] weights [b]. */
    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val u = 1f - t
        return Color.argb(
            (Color.alpha(a) * u + Color.alpha(b) * t).toInt(),
            (Color.red(a) * u + Color.red(b) * t).toInt(),
            (Color.green(a) * u + Color.green(b) * t).toInt(),
            (Color.blue(a) * u + Color.blue(b) * t).toInt(),
        )
    }

    // Pre-theme defaults, oxide. See setPlateColors.
    private const val GROUND = "#120A08"
    private const val ACCENT = "#D15D4D"
    private const val CAPTION = "#867E79"
    private const val FRAME = "#3A2A27"
}
