package stream.kleeamp.mobile.player.vis

import kotlin.math.sin

object ButterflyCore {

    fun grid(bands: FloatArray, dotRows: Int, dotCols: Int, frame: Long): BooleanArray {
        val grid = BooleanArray(dotRows * dotCols)
        if (bands.isEmpty() || dotRows <= 0 || dotCols <= 0) return grid
        val bandCount = bands.size
        val centerX = dotCols / 2

        for (dy in 0 until dotRows) {
            val bandF = dy.toDouble() / maxOf(1, dotRows - 1) * (bandCount - 1)
            val bi = bandF.toInt()
            val frac = bandF - bi
            val energy = if (bi >= bandCount - 1) {
                bands[bandCount - 1].toDouble()
            } else {
                bands[bi] * (1 - frac) + bands[bi + 1] * frac
            }
            paintWing(grid, dotCols, centerX, dy, frame, bi, energy)

            if (energy > 0.05) {
                grid[dy * dotCols + centerX] = true
                if (centerX > 0) grid[dy * dotCols + centerX - 1] = true
            }
        }
        return grid
    }

    /** One mirrored wing row: scattered dots inside an energy-scaled span. */
    private fun paintWing(
        grid: BooleanArray,
        dotCols: Int,
        centerX: Int,
        dy: Int,
        frame: Long,
        bi: Int,
        energy: Double,
    ) {
        val wobble = sin(frame * 0.08 + dy * 0.3) * 0.15
        val wingWidth = (centerX * (energy + wobble) * 0.9).toInt()
        for (dx in 0 until wingWidth) {
            val norm = dx.toDouble() / maxOf(1, wingWidth)
            var threshold = (1.0 - norm * norm) * energy
            if (norm > 0.6) {
                threshold *= 0.5 + 0.5 * sin(frame * 0.1 + dy * 0.5 + dx * 0.3)
            }
            if (VisMath.scatterHash(bi, dy, dx, frame / 3) < threshold) {
                val rx = centerX + dx
                if (rx < dotCols) grid[dy * dotCols + rx] = true
                val lx = centerX - 1 - dx
                if (lx >= 0) grid[dy * dotCols + lx] = true
            }
        }
    }
}
