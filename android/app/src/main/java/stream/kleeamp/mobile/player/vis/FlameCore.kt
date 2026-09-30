package stream.kleeamp.mobile.player.vis

/**
 * Doom-fire propagation, ported from cliamp's flameDriver
 * (`ui/vis_flame.go`): a heat field fed at the bottom row from a smooth
 * spectrum sample plus sparkle (so a bed of embers survives quiet input),
 * then every cell inherits a wind-jittered neighbour below with tapered
 * random decay - a continuous lapping flame, not independent columns.
 *
 * Buffer row 0 is the BOTTOM (the source), like the Go heat buffer; the
 * renderer flips vertically. Grid tiers: heat >= 0.55 reads yellow-hot,
 * lower heat red, wispy tips stochastically culled at draw time.
 */
class FlameCore(seed: Long = 0xF1A3C0DE0BADCAFEu.toLong()) {

    var dotRows: Int = 0
        private set
    var dotCols: Int = 0
        private set

    /** Heat per cell, row 0 = bottom source row. Resized by [ensure]. */
    var heat: FloatArray = FloatArray(0)
        private set

    private var rng = seed

    fun ensure(rows: Int, cols: Int) {
        if (rows == dotRows && cols == dotCols && heat.size == rows * cols) return
        dotRows = rows
        dotCols = cols
        heat = FloatArray(rows * cols)
    }

    private fun rand100(): Int {
        rng = rng * 6364136223846793005L + 1442695040888963407L
        return ((rng ushr 33) % 100).toInt()
    }

    fun push(bands: FloatArray) {
        if (dotRows < 4 || dotCols < 4 || heat.size != dotRows * dotCols) return
        // Source row: smooth spectrum sample + sparkle + ember floor.
        if (bands.isNotEmpty()) {
            val last = (bands.size - 1).toDouble()
            for (x in 0 until dotCols) {
                val pos = x.toDouble() / maxOf(1, dotCols - 1) * last
                val src = VisMath.sampleLinear(bands, pos.toFloat())
                val sparkle = rand100() / 100.0 * 0.18
                var base = 0.30 + 0.70 * src + sparkle
                if (base > 1.05) base = 1.05
                heat[x] = base.toFloat()
            }
        } else {
            for (x in 0 until dotCols) {
                heat[x] = (0.30 + rand100() / 100.0 * 0.20).toFloat()
            }
        }
        // Propagate upward (buffer grows downward from the source): top-down
        // so each cell reads the row below before it is overwritten.
        for (y in dotRows - 1 downTo 1) {
            // Flames taper: decay grows with height.
            val heightFrac = y.toDouble() / maxOf(1, dotRows - 1)
            val decayBase = 0.010 + 0.028 * heightFrac
            for (x in 0 until dotCols) {
                // One LCG step feeds both the wind offset and the decay.
                rng = rng * 6364136223846793005L + 1442695040888963407L
                val r = rng ushr 33
                val offset = (r % 3).toInt() - 1
                val decayJitter = ((r shr 2) % 100).toInt() / 100.0 * 0.018
                val sourceX = (x + offset).coerceIn(0, dotCols - 1)
                var next = heat[(y - 1) * dotCols + sourceX] - decayBase - decayJitter
                if (next < 0) next = 0.0
                heat[y * dotCols + x] = next.toFloat()
            }
        }
    }

    /** Heat at buffer ([x], [y]) with y = 0 at the bottom, else 0. */
    fun heatAt(x: Int, y: Int): Float {
        if (x !in 0 until dotCols || y !in 0 until dotRows) return 0f
        return heat[y * dotCols + x]
    }

    fun settle() {
        heat.fill(0f)
    }
}
