package stream.kleeamp.mobile.player.vis

import kotlin.random.Random

/**
 * Static heatmap tiles, mirroring cliamp's mosaicDriver: the grid never
 * scrolls, each cell listens to one band past a personal threshold and
 * decays in place, so loud passages saturate speckled while quiet ones
 * keep only the most sensitive cells lit.
 */
class MosaicCore(
    val rows: Int,
    val cols: Int,
    seed: Long = 0xC1AB1A1015D5,
) {
    private val rng = Random(seed)
    val bandOf = IntArray(rows * cols)
    val threshold = FloatArray(rows * cols)
    val value = FloatArray(rows * cols)

    init {
        for (i in value.indices) {
            bandOf[i] = rng.nextInt(1_000_000)
            threshold[i] = 0.15f + rng.nextFloat() * 0.75f
        }
    }

    fun push(bands: FloatArray) {
        for (i in value.indices) {
            val level = if (bands.isEmpty()) 0f
            else bands[(bandOf[i] % bands.size + bands.size) % bands.size]
            value[i] = if (level >= threshold[i]) 1f else (value[i] - 0.06f).coerceAtLeast(0f)
        }
    }

    fun settle() {
        value.fill(0f)
    }
}
