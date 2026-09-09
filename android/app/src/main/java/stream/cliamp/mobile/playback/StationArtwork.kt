package stream.cliamp.mobile.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import stream.cliamp.mobile.R
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource
import java.io.ByteArrayOutputStream

/**
 * Radio has no cover art, and the directory's favicons are 32px JPEGs of wildly
 * varying quality. Rather than ship a blank square, we draw the same striped
 * plate the player screen shows.
 *
 * This matters more than it looks: Android's media player derives the whole
 * chip's background and accent colours from the artwork, so with no artwork the
 * notification is grey system chrome, and with this it picks up the plate's.
 * Which is why the plate is drawn in oxide and not in the palette the user
 * happens to have chosen - a station with no art of its own is the app seen
 * from outside, so it matches the launcher icon rather than the theme. Setting
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

    private fun render(context: Context, station: Station, art: Bitmap?): ByteArray {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // ground
        paint.color = Color.parseColor(GROUND)
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint)

        // 135-degree stripes, same geometry as the in-app plate
        c.save()
        c.rotate(-45f, SIZE / 2f, SIZE / 2f)
        val stripe = 22f
        val diag = SIZE * 1.5f
        var x = SIZE / 2f - diag
        var i = 0
        while (x < SIZE / 2f + diag) {
            paint.color = Color.parseColor(if (i % 2 == 0) STRIPE_A else STRIPE_B)
            c.drawRect(x, SIZE / 2f - diag, x + stripe, SIZE / 2f + diag, paint)
            x += stripe
            i++
        }
        c.restore()

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

        // Icon 103h's six bands, envelope "h", the same geometry the launcher
        // icon and CliampIcons.Mark are drawn from.
        paint.color = Color.parseColor(MARK)
        // The media player crops this square to a wide chip and keeps the
        // middle band, so the mark is sized to survive that crop whole rather
        // than to fill the square.
        val scale = MARK_WIDTH / MARK_BOX_W
        val offX = (SIZE - MARK_BOX_W * scale) / 2f - MARK_BOX_X * scale
        val offY = (SIZE - MARK_BOX_H * scale) / 2f - MARK_BOX_Y * scale
        BANDS.forEach { b ->
            c.drawRect(
                offX + b.x * scale,
                offY + b.y * scale,
                offX + (b.x + b.w) * scale,
                offY + (b.y + b.h) * scale,
                paint,
            )
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

    /** One band of the mark, on Icon 103h's 48 grid. */
    private class Band(val x: Float, val y: Float, val w: Float, val h: Float)

    private val BANDS = listOf(
        Band(5f, 20f, 5f, 8f),
        Band(12f, 12f, 5f, 24f),
        Band(19f, 4f, 5f, 40f),
        Band(26f, 14f, 5f, 20f),
        Band(33f, 18f, 5f, 12f),
        Band(40f, 22f, 3f, 4f),
    )

    // The mark's own bounding box inside the 48 grid: it is inset, so centring
    // the grid would not centre the mark.
    private const val MARK_BOX_X = 5f
    private const val MARK_BOX_Y = 4f
    private const val MARK_BOX_W = 38f
    private const val MARK_BOX_H = 40f

    /** Drawn width in the 512 plate, kept from the mark this replaces. */
    private const val MARK_WIDTH = 200f

    // OxidePalette, fixed. See the note on the object.
    private const val GROUND = "#120A08"
    private const val STRIPE_A = "#291A17"
    private const val STRIPE_B = "#1E1412"
    private const val MARK = "#D15D4D"
    private const val CAPTION = "#867E79"
    private const val FRAME = "#3A2A27"
}
