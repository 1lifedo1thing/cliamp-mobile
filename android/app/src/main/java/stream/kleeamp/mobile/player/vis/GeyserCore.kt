package stream.kleeamp.mobile.player.vis

/**
 * Particle fountain, ported from cliamp's geyserDriver (`ui/vis_geyser.go`):
 * sustained loudness holds a steady mist column (bass-weighted), bass
 * transients launch vertical jets, and every particle arcs back down under
 * gravity with lateral spray. Tiers follow the producing band: dense bass
 * paints the column red, mids yellow, the rest green.
 *
 * The dot grid (with max-tier merge) lives here like cliamp's brailleGrid:
 * the tick clears it, advances particles and stamps them; the renderer only
 * draws braille cells.
 */
class GeyserCore(seed: Long = 0xFEED5EEDL) {

    data class Particle(
        var x: Double,
        var y: Double,
        var vx: Double,
        var vy: Double,
        val tier: Byte,
        var life: Int,
    )

    var dotRows: Int = 0
        private set
    var dotCols: Int = 0
        private set

    /** 0 = empty, else 1..3 tiers with max-merge. Row-major. */
    var grid: ByteArray = ByteArray(0)
        private set

    private val particles = ArrayList<Particle>()
    private var rng = seed
    private var prevBass = 0.0

    fun particleCount(): Int = particles.size

    fun ensure(rows: Int, cols: Int) {
        if (rows == dotRows && cols == dotCols && grid.size == rows * cols) return
        dotRows = rows
        dotCols = cols
        grid = ByteArray(rows * cols)
        particles.clear()
    }

    /** Uniform [0,1), advancing cliamp's LCG exactly. */
    private fun rand01(): Double {
        rng = rng * 6364136223846793005L + 1442695040888963407L
        return ((rng ushr 33) % 1000).toDouble() / 1000.0
    }

    fun push(bands: FloatArray) {
        if (dotRows < 4 || dotCols < 4 || grid.size != dotRows * dotCols) return
        grid.fill(0)
        // cliamp analyzes DefaultSpectrumBands (10).
        val spec = if (bands.size == BANDS) bands else VisMath.resampleAverage(bands, BANDS)
        if (spec.isEmpty()) return
        val third = maxOf(1, spec.size / 3)
        val bass = spec.take(third).average()
        val mid = spec.drop(third).take(third).average()
        val high = spec.drop(2 * third).average()
        val delta = bass - prevBass
        prevBass = bass

        val jetX = dotCols / 2
        val jetSpread = maxOf(2, dotCols / 16)

        // Steady drizzle, bass-weighted.
        val steady = bass * 0.85 + mid * 0.25 + high * 0.08
        repeat((steady * 6).toInt()) {
            spawn(jetX, dotRows - 1, jetSpread, 1.5 + steady * 4.5, bass, mid)
        }
        // Transient kick: thick burst even on gentle kick drums.
        if (delta > 0.06 && bass > 0.15) {
            val burst = 40 + (delta * 180).toInt()
            repeat(burst) {
                spawn(jetX, dotRows - 1, jetSpread * 2, 4.5 + delta * 10.0 + bass * 4.0, bass, mid)
            }
        }

        // Advance: gravity pulls down, drag slows lateral motion. Past the
        // floor or its 200-frame life a particle is gone; above the top it
        // pins to row 0 until it falls back or expires.
        val live = ArrayList<Particle>(particles.size)
        for (p in particles) {
            p.vy += GRAVITY
            p.vx *= DRAG
            p.x += p.vx
            p.y += p.vy
            p.life++
            val ix = p.x.toInt()
            var iy = p.y.toInt()
            if (iy >= dotRows || ix < 0 || ix >= dotCols || p.life > LIFE) continue
            if (iy < 0) iy = 0
            val at = iy * dotCols + ix
            if (p.tier > grid[at]) grid[at] = p.tier
            live += p
        }
        particles.clear()
        particles.addAll(live)
    }

    private fun spawn(x: Int, y: Int, spread: Int, vy: Double, bass: Double, mid: Double) {
        val jx = x + (rand01() * (2 * spread + 1)).toInt() - spread
        val vyJitter = vy * (0.6 + rand01() * 0.5)
        val vxJitter = (rand01() - 0.5) * (1.0 + vy * 0.4)
        val r = rand01()
        val tier: Byte = when {
            r < bass -> 3
            r < bass + mid -> 2
            else -> 1
        }
        particles += Particle(jx.toDouble(), y.toDouble(), vxJitter, -vyJitter, tier, 0)
    }

    fun tierAt(x: Int, y: Int): Int {
        if (x !in 0 until dotCols || y !in 0 until dotRows) return 0
        return grid[y * dotCols + x].toInt()
    }

    fun settle() {
        grid.fill(0)
        particles.clear()
        prevBass = 0.0
    }

    private companion object {
        const val BANDS = 10
        const val GRAVITY = 0.30
        const val DRAG = 0.992
        const val LIFE = 200
    }
}
