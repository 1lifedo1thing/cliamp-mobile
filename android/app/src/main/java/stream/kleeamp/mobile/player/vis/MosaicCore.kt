package stream.kleeamp.mobile.player.vis

/**
 * Static heatmap tiles, ported from cliamp's mosaicDriver
 * (`ui/vis_mosaic.go`): the grid never scrolls, each cell is wired at setup
 * to one band (biased toward its row: top treble, bottom bass) with a
 * personal ignition threshold in [0.04, 0.78]. Loud passages ignite many
 * tiles, quiet ones only the most sensitive; lit cells decay in place.
 *
 * Wiring uses cliamp's LCG reseeded on every grid build, so each visit
 * reshuffles bands and thresholds like OnEnter does.
 */
class MosaicCore {

    var rows: Int = 0
        private set
    var tiles: Int = 0
        private set

    /** Wired band per cell. */
    var bandOf: IntArray = IntArray(0)
        private set

    /** Ignition threshold per cell. */
    var threshold: FloatArray = FloatArray(0)
        private set

    /** Current displayed intensity per cell. */
    var value: FloatArray = FloatArray(0)
        private set

    private var rng = SEED

    fun ensureGrid(rows: Int, tiles: Int, bandCount: Int) {
        if (rows == this.rows && tiles == this.tiles && value.size == rows * tiles) return
        this.rows = rows
        this.tiles = tiles
        bandOf = IntArray(rows * tiles)
        threshold = FloatArray(rows * tiles)
        value = FloatArray(rows * tiles)
        val bands = if (bandCount <= 0) BANDS else bandCount
        rng = SEED
        for (r in 0 until rows) {
            var baseBand = bands / 2
            if (rows > 1) baseBand = (rows - 1 - r) * (bands - 1) / (rows - 1)
            for (c in 0 until tiles) {
                rng = rng * LCG_A + LCG_C
                val jitter = ((rng ushr 33) % 5).toInt() - 2
                bandOf[r * tiles + c] = (baseBand + jitter).coerceIn(0, bands - 1)
                rng = rng * LCG_A + LCG_C
                threshold[r * tiles + c] = 0.04f + ((rng ushr 33) % 1000).toInt() / 1000f * 0.74f
            }
        }
    }

    fun push(bands: FloatArray) {
        if (bands.isEmpty()) {
            // Still decay so cells don't stick lit during silence.
            for (i in value.indices) value[i] *= DECAY
            return
        }
        for (i in value.indices) {
            val level = bands[bandOf[i].coerceIn(0, bands.size - 1)]
            if (level > threshold[i]) {
                val ignited = minOf(level, 1.05f)
                if (ignited > value[i]) value[i] = ignited
            }
            value[i] *= DECAY
            if (value[i] < 0.001f) value[i] = 0f
        }
    }

    /** Brightness tier 0..6 for an intensity, exactly like cliamp. */
    fun levelOf(intensity: Float): Int = when {
        intensity >= 0.85f -> 6 // overdrive
        intensity >= 0.65f -> 5 // hot
        intensity >= 0.45f -> 4
        intensity >= 0.28f -> 3
        intensity >= 0.15f -> 2
        intensity >= 0.05f -> 1
        else -> 0
    }

    /** Tiles fitting a width: 2-wide tiles with 1 gaps, no trailing gap. */
    fun tileCount(widthPx: Float, tilePx: Float): Int {
        if (widthPx < tilePx * 2 || tilePx <= 0f) return 0
        val step = tilePx * 3
        return ((widthPx + tilePx) / step).toInt()
    }

    fun settle() {
        value.fill(0f)
    }

    private companion object {
        const val SEED = 0xC1AB1A1015D5L
        const val LCG_A = 6364136223846793005L
        const val LCG_C = 1442695040888963407L
        const val DECAY = 0.88f
        const val BANDS = 10
    }
}
