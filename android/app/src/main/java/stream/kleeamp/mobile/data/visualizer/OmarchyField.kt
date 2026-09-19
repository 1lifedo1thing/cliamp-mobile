package stream.kleeamp.mobile.data.visualizer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

data class OmarchyPixel(val lit: Boolean, val tier: Int)

class OmarchyGlyph(val width: Int, val height: Int, val on: BooleanArray) {
    fun at(x: Int, y: Int): Boolean =
        x >= 0 && y >= 0 && x < width && y < height && on[y * width + x]

    val empty: Boolean get() = width == 0 || height == 0
}

class OmarchyField {

    private val noise = DoubleArray(NOISE_SIZE * NOISE_SIZE)
    private val wordmark: OmarchyGlyph = decodeHalfBlockArt(WORDMARK_ART)
    private val square: OmarchyGlyph = decodeBitRows(SQUARE_BITS)

    init {
        var s = 0x9E3779B97F4A7C15uL
        for (i in noise.indices) {
            s = s * 6364136223846793005uL + 1442695040888963407uL
            noise[i] = ((s shr 33) % 100000uL).toDouble() / 100000.0
        }
    }

    fun noiseAt(u0: Double, v0: Double): Double {
        var u = u0
        var v = v0
        u -= floor(u / NOISE_SIZE) * NOISE_SIZE
        v -= floor(v / NOISE_SIZE) * NOISE_SIZE
        val x0 = u.toInt()
        val y0 = v.toInt()
        val x1 = (x0 + 1) % NOISE_SIZE
        val y1 = (y0 + 1) % NOISE_SIZE
        val fx = u - x0
        val fy = v - y0
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        val a = noise[y0 * NOISE_SIZE + x0]
        val b = noise[y0 * NOISE_SIZE + x1]
        val c = noise[y1 * NOISE_SIZE + x0]
        val d = noise[y1 * NOISE_SIZE + x1]
        return (a + (b - a) * sx) * (1 - sy) + (c + (d - c) * sx) * sy
    }

    fun jitter(row: Int, col: Int): Double {
        var h = row.toULong() * 6271uL + col.toULong() * 3037uL + 0x9E3779B9uL
        h = h xor (h shr 16)
        h *= 0x45D9F3B37197344BuL
        h = h xor (h shr 16)
        return (h % 10000uL).toDouble() / 10000.0
    }

    private fun fitScale(g: OmarchyGlyph, pxRows: Int, pxCols: Int): Int {
        if (g.empty) return 0
        if (pxRows < g.height + MARK_MARGIN || pxCols < g.width + MARK_MARGIN) return 0
        return minOf((pxRows - MARK_MARGIN) / g.height, (pxCols - MARK_MARGIN) / g.width, MAX_SCALE)
    }

    private fun markFor(pxRows: Int, pxCols: Int): Pair<OmarchyGlyph, Int> {
        val scale = fitScale(wordmark, pxRows, pxCols)
        if (scale > 0) return wordmark to scale
        val squareScale = fitScale(square, pxRows, pxCols)
        return if (squareScale > 0) square to squareScale else OmarchyGlyph(0, 0, BooleanArray(0)) to 0
    }

    fun sampler(bands: FloatArray, frame: Long, pxRows: Int, pxCols: Int): Sampler {
        val amp = if (bands.isEmpty()) 0.0 else bands.sumOf { it.toDouble() } / bands.size
        val (glyph, scale) = markFor(pxRows, pxCols)
        val markW = glyph.width * scale
        val markH = glyph.height * scale
        return Sampler(
            bands = bands,
            amp = amp,
            t = frame * 0.03,
            glyph = glyph,
            scale = scale,
            markX = (pxCols - markW) / 2,
            markY = (pxRows - markH) / 2,
            markW = markW,
            markH = markH,
            pxRows = pxRows,
            pxCols = pxCols,
        )
    }

    inner class Sampler(
        private val bands: FloatArray,
        private val amp: Double,
        private val t: Double,
        private val glyph: OmarchyGlyph,
        private val scale: Int,
        private val markX: Int,
        private val markY: Int,
        private val markW: Int,
        private val markH: Int,
        private val pxRows: Int,
        private val pxCols: Int,
    ) {

        private fun levelAt(pc: Int): Double {
            val n = bands.size
            if (n == 0) return 0.0
            val half = pxCols / 2.0
            val side = min(1.0, abs(pc + 0.5 - half) / max(half, 1.0))
            val pos = (1 - side) * n - 0.5
            val b0 = min(max(floor(pos).toInt(), 0), n - 1)
            val b1 = min(b0 + 1, n - 1)
            val mixB = max(0.0, min(1.0, pos - b0))
            val raw = bands[b0] * (1 - mixB) + bands[b1] * mixB
            return max(0.0, (raw - SPECTRUM_FLOOR) / (1 - SPECTRUM_FLOOR))
        }

        private fun shadeAt(pr: Int, pc: Int): Double {
            if (scale == 0) return 1.0
            val dx = max(0.0, max((markX - pc).toDouble(), (pc - (markX + markW - 1)).toDouble()))
            val dy = max(0.0, max((markY - pr).toDouble(), (pr - (markY + markH - 1)).toDouble()))
            return min(1.0, hypot(dx, dy) / CLEAR_REACH).pow(CLEAR_CURVE)
        }

        fun pixel(pr: Int, pc: Int): OmarchyPixel {
            if (scale > 0 && pc >= markX && pr >= markY && pc < markX + markW && pr < markY + markH &&
                glyph.at((pc - markX) / scale, (pr - markY) / scale)
            ) {
                return OmarchyPixel(true, VisMath.tier((0.34 + levelAt(pc) * 0.35 + amp * 0.45).toFloat()))
            }

            val shade = shadeAt(pr, pc)
            if (shade < 0.004) return OmarchyPixel(false, 0)

            var spec = 0.0
            val level = levelAt(pc)
            if (level > 0) {
                val up = (pxRows - 1 - pr).toDouble()
                val tall = level * pxRows * SPECTRUM_REACH
                if (up < tall) spec = level * (1 - up / tall).pow(0.85)
            }

            val u = pc / CELLS_PER_NOISE
            val w = pr / CELLS_PER_NOISE
            val base = 0.6 * noiseAt(u + t * 0.14, w - t * 0.055) +
                0.4 * noiseAt(u * 0.55 - t * 0.08, w * 0.55 + t * 0.06)
            val tw = 0.5 + 0.5 * sin(t * 1.1 + jitter(pr, pc) * 2 * PI)
            val lum = shade * (0.30 + 0.52 * base * base + 0.18 * tw + amp * 0.22) * REST +
                spec * 0.72 * min(1.0, shade * 3)

            val threshold = 0.55 * ((BAYER[(pr and 7) * 8 + (pc and 7)] + 0.5) / 64.0) +
                0.45 * jitter(pr + 7, pc + 13)
            if (lum <= threshold) return OmarchyPixel(false, 0)
            return OmarchyPixel(true, VisMath.tier((spec * 0.55 + amp * 0.12).toFloat()))
        }
    }

    private fun decodeHalfBlockArt(art: List<String>): OmarchyGlyph {
        val width = art.maxOfOrNull { it.length } ?: 0
        val glyph = OmarchyGlyph(width, art.size * 2, BooleanArray(width * art.size * 2))
        art.forEachIndexed { row, line ->
            line.forEachIndexed { col, ch ->
                val upper = ch == '█' || ch == '▀'
                val lower = ch == '█' || ch == '▄'
                if (upper) glyph.on[(row * 2) * width + col] = true
                if (lower) glyph.on[(row * 2 + 1) * width + col] = true
            }
        }
        return glyph
    }

    private fun decodeBitRows(rows: List<String>): OmarchyGlyph {
        val width = rows.maxOfOrNull { it.length } ?: 0
        val glyph = OmarchyGlyph(width, rows.size, BooleanArray(width * rows.size))
        rows.forEachIndexed { row, line ->
            line.forEachIndexed { col, ch ->
                if (ch == '1') glyph.on[row * width + col] = true
            }
        }
        return glyph
    }

    private companion object {
        const val CELLS_PER_NOISE = 6.0
        const val SPECTRUM_FLOOR = 0.06
        const val SPECTRUM_REACH = 0.95
        const val REST = 0.34
        const val CLEAR_REACH = 6.0
        const val CLEAR_CURVE = 3.0
        const val MAX_SCALE = 3
        const val MARK_MARGIN = 2
        const val NOISE_SIZE = 64

        val BAYER = intArrayOf(
            0, 32, 8, 40, 2, 34, 10, 42,
            48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38,
            60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41,
            51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37,
            63, 31, 55, 23, 61, 29, 53, 21,
        )

        val WORDMARK_ART = listOf(
            "                 ▄▄▄",
            " ▄█████▄    ▄███████████▄    ▄███████   ▄███████   ▄███████   ▄█   █▄    ▄█   █▄",
            "███   ███  ███   ███   ███  ███   ███  ███   ███  ███   ███  ███   ███  ███   ███",
            "███   ███  ███   ███   ███  ███   ███  ███   ███  ███   █▀   ███   ███  ███   ███",
            "███   ███  ███   ███   ███ ▄███▄▄▄███ ▄███▄▄▄██▀  ███       ▄███▄▄▄███▄ ███▄▄▄███",
            "███   ███  ███   ███   ███ ▀███▀▀▀███ ▀███▀▀▀▀    ███      ▀▀███▀▀▀███  ▀▀▀▀▀▀███",
            "███   ███  ███   ███   ███  ███   ███ ██████████  ███   █▄   ███   ███  ▄██   ███",
            "███   ███  ███   ███   ███  ███   ███  ███   ███  ███   ███  ███   ███  ███   ███",
            " ▀█████▀    ▀█   ███   █▀   ███   █▀   ███   ███  ███████▀   ███   █▀    ▀█████▀",
            "                                       ███   █▀",
        )

        val SQUARE_BITS = listOf(
            "111111111111111",
            "100000010000001",
            "101111110001101",
            "101000000000101",
            "101000000000101",
            "101000000000101",
            "101000000000101",
            "111000000000101",
            "101000000000101",
            "101000000000101",
            "101000000000101",
            "101000000000101",
            "101111111111101",
            "100000010000001",
            "111111110111111",
        )
    }
}
