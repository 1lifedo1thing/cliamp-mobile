package stream.kleeamp.mobile.player.vis

import kotlin.random.Random

/**
 * Doom-fire propagation, mirroring cliamp's flameDriver: a heat field fed
 * at the bottom row from the spectrum, each cell inheriting its
 * neighbour-below's heat with lateral wind jitter and decay. Bass
 * thickens the source; quiet passages settle into coals.
 *
 * Fixed cell grid (independent of pixels); the renderer scales cells up.
 */
class FlameCore(
    val cols: Int,
    val rows: Int,
    seed: Long = 0xF1A3C0DE0BADCAFEu.toLong(),
) {
    val heat = FloatArray(cols * rows)

    private val rng = Random(seed)
    private var wind = 0

    /** 0 = empty avanza; heat tiers mirror the green/yellow/red ramp. */
    fun tierAt(col: Int, row: Int): Int {
        val h = heat[row * cols + col]
        return when {
            h >= 0.62f -> 2
            h >= 0.30f -> 1
            h > 0.02f -> 0
            else -> -1
        }
    }

    fun push(bands: FloatArray) {
        // Feed the bottom row from the spectrum, bass-boosted.
        for (x in 0 until cols) {
            val band = VisMath.sampleLinear(bands, x.toFloat() / cols.coerceAtLeast(1) * (bands.size - 1))
            val bass = VisMath.sampleLinear(bands, 0.5f)
            heat[(rows - 1) * cols + x] = (band * 0.75f + bass * 0.45f).coerceIn(0f, 1f)
        }
        // Propagate upward with wind jitter and decay.
        for (y in rows - 2 downTo 0) {
            for (x in 0 until cols) {
                wind = (wind + rng.nextInt(-1, 2)).coerceIn(-2, 2)
                val below = (heat[(y + 1) * cols + x] +
                    heat[(y + 1) * cols + ((x + wind).coerceIn(0, cols - 1))] +
                    heat[(y + 1) * cols + ((x - wind).coerceIn(0, cols - 1))]) / 3f
                heat[y * cols + x] = (below - 0.028f - rng.nextFloat() * 0.03f).coerceAtLeast(0f)
            }
        }
    }

    fun settle() {
        heat.fill(0f)
    }
}
