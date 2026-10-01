package stream.kleeamp.mobile.player.vis

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Wireframe equalizer after the vector part of the Red Sector Inc. RSI
 * Megademo, ported from cliamp's redSectorDriver (`ui/vis_red_sector.go`):
 * five hollow bars stand on a common ground line, each driven by the louder
 * of two spectrum bands through its own ceiling/floor envelope (so bars show
 * where the level sits in its own range, easing fast up and slow down),
 * while the group tumbles as a rigid body with backface-culled faces and a
 * drifting starfield behind it.
 *
 * Cells keep the highest tag drawn into them: the four star tags sit below
 * the three bar tags, so bars always win shared cells.
 */
class RedSectorCore {

    var dotRows: Int = 0
        private set
    var dotCols: Int = 0
        private set

    /** Tag per dot cell; stars 1..4, bars 5..7. */
    var cells: ByteArray = ByteArray(0)
        private set

    private val ceiling = DoubleArray(BARS)
    private val floor = DoubleArray(BARS)
    private val heights = DoubleArray(BARS)

    init {
        reset()
    }

    fun reset() {
        for (i in 0 until BARS) {
            ceiling[i] = 0.0
            floor[i] = 1.0
            heights[i] = MIN_HEIGHT
        }
        cells = ByteArray(0)
        dotRows = 0
        dotCols = 0
    }

    fun ensure(rows: Int, cols: Int) {
        if (rows == dotRows && cols == dotCols && cells.size == rows * cols) {
            cells.fill(0)
            return
        }
        dotRows = rows
        dotCols = cols
        cells = ByteArray(rows * cols)
    }

    /** Bar heights in world units, eased toward their envelope targets. */
    fun heights(): DoubleArray = heights.copyOf()

    /**
     * Advance envelopes and heights from 10 spectrum bands, exactly like
     * cliamp's advance: per-bar max of its band pair, slow ceiling/floor
     * tracking, 0.75 attack / 0.28 release toward the envelope target.
     */
    fun advance(bands: FloatArray) {
        for (i in 0 until BARS) {
            val level = max(band(bands, i * 2), band(bands, i * 2 + 1))
            if (level > ceiling[i]) ceiling[i] = level else ceiling[i] -= RELAX
            if (level < floor[i]) floor[i] = level else floor[i] += RELAX
            if (ceiling[i] < floor[i] + MIN_ENVELOPE) ceiling[i] = floor[i] + MIN_ENVELOPE
            val norm = ((level - floor[i]) / (ceiling[i] - floor[i])).coerceIn(0.0, 1.0)
            val target = MIN_HEIGHT + norm * (MAX_HEIGHT - MIN_HEIGHT)
            val rate = if (target > heights[i]) ATTACK else RELEASE
            heights[i] += (target - heights[i]) * rate
        }
    }

    private fun band(bands: FloatArray, i: Int): Double =
        if (i >= bands.size) 0.0 else bands[i].toDouble().coerceIn(0.0, 1.0)

    /** Bar color tag off its height, using the spectrum thresholds. */
    fun barTag(height: Double): Byte {
        val shown = (height - MIN_HEIGHT) / (MAX_HEIGHT - MIN_HEIGHT)
        return when {
            shown >= 0.6 -> TAG_HIGH
            shown >= 0.3 -> TAG_MID
            else -> TAG_LOW
        }.toByte()
    }

    /**
     * Deterministic star hash in [0,1), splitmix64 finalizer exactly like
     * cliamp: mixing (not scaling) the index breaks diagonal structure.
     */
    fun starHash(i: Int, slot: Int): Double {
        var x = i.toULong() * 0x9E3779B97F4A7C15uL + (slot + 1).toULong() * 0xD1B54A32D192ED03uL
        x = x xor (x shr 30); x *= 0xBF58476D1CE4E5B9uL; x = x xor (x shr 27)
        x *= 0x94D049BB133111EBuL; x = x xor (x shr 31)
        return ((x shr 11).toDouble() / (1uL shl 53).toDouble())
    }

    /**
     * Rotate (x, y, z) around Y then X and project: rotated point plus
     * projected world position, exactly like redSectorPlace.
     */
    fun place(
        x: Double, y: Double, z: Double,
        cosX: Double, sinX: Double, cosY: Double, sinY: Double, camZ: Double,
    ): DoubleArray {
        val x1 = x * cosY + z * sinY
        val z1 = -x * sinY + z * cosY
        val y1 = y * cosX - z1 * sinX
        val z2 = y * sinX + z1 * cosX
        val depth = max(0.35, z2 + camZ)
        val f = FOCAL / depth
        return doubleArrayOf(x1, y1, z2, x1 * f, y1 * f)
    }

    /** Face visibility: wound inwards, visible when the normal points away. */
    fun faceVisible(p1: DoubleArray, p2: DoubleArray, p3: DoubleArray, camZ: Double): Boolean {
        val ux = p2[0] - p1[0]; val uy = p2[1] - p1[1]; val uz = p2[2] - p1[2]
        val vx = p3[0] - p2[0]; val vy = p3[1] - p2[1]; val vz = p3[2] - p2[2]
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        return nx * p1[0] + ny * p1[1] + nz * (p1[2] + camZ) > 0
    }

    /** Star count for a grid: area/130 held inside [6, 90]. */
    fun starCount(): Int = min(MAX_STARS, max(MIN_STARS, dotRows * dotCols / DOTS_PER_STAR))

    /**
     * Draw the full scene at [frame] ticks: starfield first, then the
     * tumbling bars on top (higher tags win shared cells).
     */
    fun draw(frame: Long) {
        cells.fill(0)
        drawStars(frame)
        drawBars(frame)
    }

    private fun drawStars(frame: Long) {
        val count = starCount()
        val f = frame.toDouble()
        for (i in 1..count) {
            // Three speed lanes give the field shallow depth.
            val speed = 0.10 + (i % 3) * 0.09
            var x = (starHash(i, 0) * dotCols - f * speed) % dotCols
            if (x < 0) x += dotCols
            val y = (starHash(i, 1) * dotRows).toInt()
            val hue = min(STAR_TAGS, 1 + (starHash(i, 2) * STAR_TAGS).toInt())
            set(x.toInt(), y, hue.toByte())
        }
    }

    private fun drawBars(frame: Long) {
        val f = frame.toDouble()
        val cosY = cos(f * SPIN_Y); val sinY = sin(f * SPIN_Y)
        val cosX = cos(f * SPIN_X); val sinX = sin(f * SPIN_X)
        val camZ = CAM_Z + sin(f * ZOOM_RATE) * ZOOM_AMP
        val fit = fit(cosX, sinX, cosY, sinY, camZ, f)
        val fitX = fit[0]; val fitY = fit[1]
        val centreX = fit[2]; val centreY = fit[3]

        val rx = DoubleArray(8); val ry = DoubleArray(8); val rz = DoubleArray(8)
        val px = DoubleArray(8); val py = DoubleArray(8)
        for (bar in 0 until BARS) {
            val baseX = (bar - (BARS - 1) / 2.0) * PITCH
            val topY = GROUND_Y + heights[bar]
            val tag = barTag(heights[bar])
            for (c in 0 until 8) {
                val y = if (CORNER_TOP[c]) topY else GROUND_Y
                val r = place(
                    baseX + CORNER_X[c] * HALF_W, y, CORNER_Z[c] * HALF_D,
                    cosX, sinX, cosY, sinY, camZ,
                )
                rx[c] = r[0]; ry[c] = r[1]; rz[c] = r[2]
                px[c] = centreX + r[3] * fitX
                py[c] = centreY - r[4] * fitY
            }
            for (face in FACES) {
                val i1 = face[0]; val i2 = face[1]; val i3 = face[2]
                if (!faceVisible(
                        doubleArrayOf(rx[i1], ry[i1], rz[i1]),
                        doubleArrayOf(rx[i2], ry[i2], rz[i2]),
                        doubleArrayOf(rx[i3], ry[i3], rz[i3]), camZ,
                    )
                ) {
                    continue
                }
                for (e in 0 until 4) {
                    val from = face[e]; val to = face[(e + 1) % 4]
                    drawLine(px[from], py[from], px[to], py[to], tag)
                }
            }
        }
    }

    /**
     * Fit scales against the hull the object can never exceed (not the live
     * bars), with viewer zoom breathing and widening on short panels.
     */
    fun fit(
        cosX: Double, sinX: Double, cosY: Double, sinY: Double, camZ: Double, frame: Double,
    ): DoubleArray {
        val hullX = (BARS - 1) / 2.0 * PITCH + HALF_W
        var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        for (sx in doubleArrayOf(-1.0, 1.0)) {
            for (y in doubleArrayOf(GROUND_Y, GROUND_Y + MAX_HEIGHT)) {
                for (sz in doubleArrayOf(-1.0, 1.0)) {
                    val r = place(sx * hullX, y, sz * HALF_D, cosX, sinX, cosY, sinY, camZ)
                    minX = min(minX, r[3]); maxX = max(maxX, r[3])
                    minY = min(minY, r[4]); maxY = max(maxY, r[4])
                }
            }
        }
        val spanX = max(maxX - minX, 0.001)
        val spanY = max(maxY - minY, 0.001)
        val zoom = 0.55 + 0.30 * (0.5 + 0.5 * sin(frame * ZOOM_RATE))
        val fitY = (dotRows - 1) / spanY * zoom
        val stretch = min(MAX_STRETCH, max(1.0, COMFORT_ROWS / dotRows))
        val fitX = min(fitY * stretch, (dotCols - 1) / spanX)
        val centreX = dotCols / 2.0 - (minX + maxX) / 2 * fitX
        val centreY = dotRows / 2.0 + (minY + maxY) / 2 * fitY
        return doubleArrayOf(fitX, fitY, centreX, centreY)
    }

    /** Edge rasterizer with a step cap against degenerate projections. */
    fun drawLine(x0: Double, y0: Double, x1: Double, y1: Double, tag: Byte) {
        val dx = x1 - x0; val dy = y1 - y0
        val span = max(abs(dx), abs(dy))
        if (span.isNaN() || span.isInfinite()) return
        val steps = min(MAX_LINE_STEPS, span.toInt() + 1)
        for (s in 0..steps) {
            val t = s.toDouble() / steps
            val x = floor(x0 + dx * t + 0.5).toInt()
            val y = floor(y0 + dy * t + 0.5).toInt()
            set(x, y, tag)
        }
    }

    fun set(x: Int, y: Int, tag: Byte) {
        if (x < 0 || x >= dotCols || y < 0 || y >= dotRows) return
        val at = y * dotCols + x
        if (tag > cells[at]) cells[at] = tag
    }

    fun tagAt(x: Int, y: Int): Int {
        if (x !in 0 until dotCols || y !in 0 until dotRows) return 0
        return cells[y * dotCols + x].toInt()
    }

    fun settle() {
        reset()
    }

    companion object {
        const val BARS = 5
        const val HALF_W = 0.26
        const val HALF_D = 0.26
        const val PITCH = 0.78
        const val GROUND_Y = -1.05
        const val MIN_HEIGHT = 0.40
        const val MAX_HEIGHT = 2.30
        const val FOCAL = 3.2
        const val CAM_Z = 6.0
        const val ZOOM_AMP = 1.5
        const val MAX_STRETCH = 2.0
        const val COMFORT_ROWS = 40.0
        const val SPIN_Y = 0.105
        const val SPIN_X = 0.073
        const val ZOOM_RATE = 0.011
        const val RELAX = 0.001
        const val MIN_ENVELOPE = 0.06
        const val ATTACK = 0.75
        const val RELEASE = 0.28
        const val STAR_TAGS = 4
        const val TAG_LOW = 5
        const val TAG_MID = 6
        const val TAG_HIGH = 7
        const val MIN_STARS = 6
        const val MAX_STARS = 90
        const val DOTS_PER_STAR = 130
        const val MAX_LINE_STEPS = 512

        val CORNER_X = doubleArrayOf(-1.0, 1.0, 1.0, -1.0, -1.0, 1.0, 1.0, -1.0)
        val CORNER_Z = doubleArrayOf(-1.0, -1.0, -1.0, -1.0, 1.0, 1.0, 1.0, 1.0)
        val CORNER_TOP = booleanArrayOf(false, false, true, true, false, false, true, true)
        val FACES = arrayOf(
            intArrayOf(0, 1, 2, 3),
            intArrayOf(5, 4, 7, 6),
            intArrayOf(0, 3, 7, 4),
            intArrayOf(1, 5, 6, 2),
            intArrayOf(3, 2, 6, 7),
            intArrayOf(0, 4, 5, 1),
        )
    }
}
