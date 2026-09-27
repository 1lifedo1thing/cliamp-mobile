package stream.kleeamp.mobile.player.vis

import kotlin.random.Random

/**
 * Falling-sand automaton, mirroring cliamp's sandDriver: grains pour from
 * the top tinted by the band that triggered them, fall straight down or
 * slide diagonally onto piles, and bass shakes the bed into little
 * avalanches. A strong bass transient fires a ballistic shower that
 * suspends the pour while particles fly.
 *
 * Fixed cell grid (independent of pixels); the renderer scales cells up.
 */
class SandCore(
    val cols: Int,
    val rows: Int,
    seed: Long = 0xC1AB1A1015D5,
) {
    data class Grain(var x: Float, var y: Float, val vx: Float, var vy: Float, val tier: Int, var life: Int)

    /** 0 = empty, else 1..3 heat tiers. */
    val grid = ByteArray(cols * rows)

    private val rng = Random(seed)
    private var prevBass = 0f
    private val shower = ArrayList<Grain>()
    private var showerTtl = 0

    val showerActive: Boolean get() = showerTtl > 0

    fun push(bands: FloatArray) {
        val bass = VisMath.sampleLinear(bands, 0.5f)
        // Bass transient fires the shower.
        if (bass - prevBass > 0.35f && showerTtl == 0) {
            showerTtl = 40
            repeat(60) {
                shower += Grain(
                    x = rng.nextFloat() * cols,
                    y = rng.nextFloat() * rows * 0.4f,
                    vx = rng.nextFloat() * 2f - 1f,
                    vy = -(rng.nextFloat() * 2f + 1f),
                    tier = 1 + rng.nextInt(3),
                    life = 30 + rng.nextInt(20),
                )
            }
        }
        prevBass = bass
        if (showerTtl > 0) tickShower() else tickFall(bands, bass)
    }

    /** Ballistic phase: the grid re-derives from particle positions. */
    private fun tickShower() {
        showerTtl--
        val it = shower.iterator()
        while (it.hasNext()) {
            val g = it.next()
            g.life--
            if (g.life <= 0) {
                it.remove()
                continue
            }
            g.x = (g.x + g.vx).coerceIn(0f, (cols - 1).toFloat())
            g.y = (g.y + g.vy).coerceAtMost(rows - 1f)
            g.vy += 0.25f
            if (g.y >= rows - 1f) {
                g.vy *= -0.3f
            }
        }
        // Rewrite the grid from the live particles.
        grid.fill(0)
        for (g in shower) {
            grid[g.y.toInt().coerceIn(0, rows - 1) * cols + g.x.toInt().coerceIn(0, cols - 1)] =
                g.tier.toByte()
        }
        if (showerTtl == 0) shower.clear()
    }

    /** Normal phase: pour tinted grains, then fall or slide with shake. */
    private fun tickFall(bands: FloatArray, bass: Float) {
        // Pour new grains tinted by band.
        val pour = 1 + (average(bands) * 4).toInt()
        repeat(pour) {
            val x = rng.nextInt(cols)
            val band = VisMath.sampleLinear(bands, x.toFloat() / cols * (bands.size - 1))
            if (band > 0.25f) {
                grid[x] = when {
                    band > 0.7f -> 3
                    band > 0.45f -> 2
                    else -> 1
                }.toByte()
            }
        }
        // Fall or slide.
        for (y in rows - 2 downTo 0) {
            var x = 0
            if (rng.nextFloat() < bass * 0.3f) x = rng.nextInt(cols) // shake bump
            while (x < cols) {
                fallCell(x, y)
                x++
            }
        }
    }

    /** One grain falls straight down or slides diagonally onto a pile. */
    private fun fallCell(x: Int, y: Int) {
        val g = grid[y * cols + x]
        if (g == 0.toByte()) return
        if (grid[(y + 1) * cols + x] == 0.toByte()) {
            grid[(y + 1) * cols + x] = g
            grid[y * cols + x] = 0
            return
        }
        val dir = if (rng.nextBoolean()) 1 else -1
        val nx = (x + dir).coerceIn(0, cols - 1)
        if (grid[(y + 1) * cols + nx] == 0.toByte() && grid[y * cols + nx] == 0.toByte()) {
            grid[(y + 1) * cols + nx] = g
            grid[y * cols + x] = 0
        }
    }

    fun grains(): List<Grain> = shower.toList()

    private fun average(bands: FloatArray): Float {
        if (bands.isEmpty()) return 0f
        var sum = 0f
        for (b in bands) sum += b
        return sum / bands.size
    }

    fun settle() {
        grid.fill(0)
        shower.clear()
        showerTtl = 0
        prevBass = 0f
    }
}
