package stream.kleeamp.mobile.player.vis

import kotlin.math.max
import kotlin.random.Random

/**
 * Falling-sand cellular automaton on a dot grid, ported from cliamp's
 * sandDriver (`ui/vis_sand.go`): grains pour from the top tinted by the
 * spectrum band that triggered them (low = red, mid = yellow, high =
 * green), fall straight down or slide diagonally onto piles, and bass
 * shakes the bed - transient kicks lift the whole bed, sustained bass
 * churns it, and an overfilled bed detonates into a ballistic explosion
 * phase that suspends the pour while particles fly.
 *
 * Grid tiers mirror the Go `int8` grid: 0 = empty, 1 = green, 2 = yellow,
 * 3 = red. The renderer maps tier-1 through [visTier].
 *
 * The grid is sized by [ensure], like cliamp sizing to the terminal: the
 * renderer calls it from canvas dimensions and a resize clears the bed,
 * exactly like a terminal resize does.
 */
class SandCore(seed: Long = 0x5A4D5A4D5A4DL) {

    var dotRows: Int = 0
        private set
    var dotCols: Int = 0
        private set

    /** 0 = empty, else 1..3 heat tiers. Row-major, resized by [ensure]. */
    var grid: ByteArray = ByteArray(0)
        private set

    private val rng = Random(seed)
    private var prevBass = 0f

    private data class Particle(var x: Double, var y: Double, var vx: Double, var vy: Double, val tier: Byte)

    private val particles = ArrayList<Particle>()
    private var explosionTtl = 0

    val explosionActive: Boolean get() = explosionTtl > 0 || particles.isNotEmpty()

    fun ensure(rows: Int, cols: Int) {
        if (rows == dotRows && cols == dotCols && grid.size == rows * cols) return
        dotRows = rows
        dotCols = cols
        grid = ByteArray(rows * cols)
        particles.clear()
        explosionTtl = 0
    }

    fun push(bands: FloatArray, frame: Long) {
        if (dotRows < 4 || dotCols < 4 || grid.size != dotRows * dotCols) return
        // cliamp analyzes DefaultSpectrumBands (10); resample anything else.
        val spec = if (bands.size == BANDS) bands else VisMath.resampleAverage(bands, BANDS)
        val bass = spec.take(spec.size / 3).average().toFloat()

        if (explosionTtl > 0 || particles.isNotEmpty()) {
            tickExplosion()
            prevBass = bass
            return
        }

        spawn(spec)
        val delta = bass - prevBass
        prevBass = bass

        // Explosion check first: an overfilled bed clears via burst instead
        // of merely shaking.
        if (delta > 0.06f && bass > 0.15f && fillFraction() > 0.30f) {
            startExplosion()
            return
        }
        if (delta > 0.06f && bass > 0.15f) transientBump(bass, delta)
        if (bass > 0.30f) rumble(bass)
        fall(frame)
        drainFloor()
    }

    /** Grain tier at ([x], [y]), 0 when empty or out of bounds. */
    fun tierAt(x: Int, y: Int): Int {
        if (x !in 0 until dotCols || y !in 0 until dotRows) return 0
        return grid[y * dotCols + x].toInt()
    }

    fun settle() {
        grid.fill(0)
        particles.clear()
        explosionTtl = 0
        prevBass = 0f
    }

    // Each band emits at its proportional column with a small spread so
    // neighbours don't stack into one tower. Gated and throttled by level.
    private fun spawn(spec: FloatArray) {
        val n = spec.size
        if (n == 0) return
        for (b in 0 until n) {
            val level = spec[b]
            if (level < 0.10f) continue
            if (rng.nextFloat() > level * 0.85f) continue
            val centre = (b * 2 + 1) * dotCols / (2 * n)
            var spread = dotCols / (n * 2)
            if (spread < 1) spread = 1
            var x = centre + (rng.nextFloat() * (2 * spread)).toInt() - spread
            x = x.coerceIn(0, dotCols - 1)
            val tier: Byte = when {
                b < n / 3 -> 3 // red bass
                b < 2 * n / 3 -> 2 // yellow mids
                else -> 1 // green highs
            }
            if (grid[x] == 0.toByte()) grid[x] = tier
        }
    }

    // Rising bass edge: violent vertical lift across the whole bed, bottom
    // grains thrown highest, with lateral spray. Top-down so a lifted grain
    // isn't visited again this frame.
    private fun transientBump(bass: Float, delta: Float) {
        var strength = delta * 3.5f + bass * 0.8f
        if (strength > 1.4f) strength = 1.4f
        for (y in 0 until dotRows) {
            val depth = y.toFloat() / max(1, dotRows - 1)
            var liftProb = strength * (0.30f + 0.70f * depth)
            if (liftProb > 0.95f) liftProb = 0.95f
            val liftMax = 2 + (strength * 7f * (0.4f + 0.6f * depth)).toInt()
            val jitterRange = 1 + (strength * 5f).toInt()
            for (x in 0 until dotCols) {
                val g = grid[y * dotCols + x]
                if (g == 0.toByte()) continue
                if (rng.nextFloat() > liftProb) continue
                val lift = 1 + (rng.nextFloat() * liftMax).toInt()
                val jitter = (rng.nextFloat() * (2 * jitterRange + 1)).toInt() - jitterRange
                val ny = max(0, y - lift)
                val nx = (x + jitter).coerceIn(0, dotCols - 1)
                if (grid[ny * dotCols + nx] == 0.toByte()) {
                    grid[ny * dotCols + nx] = g
                    grid[y * dotCols + x] = 0
                }
            }
        }
    }

    // Sustained rumble: high bass churns the bottom half every frame so the
    // bed never settles still during a heavy passage.
    private fun rumble(bass: Float) {
        var rumble = (bass - 0.30f) * 1.8f
        if (rumble > 0.6f) rumble = 0.6f
        val minY = dotRows / 2
        for (y in minY until dotRows) {
            val depth = (y - minY).toFloat() / max(1, dotRows - 1 - minY)
            val prob = rumble * (0.15f + 0.55f * depth)
            for (x in 0 until dotCols) {
                val g = grid[y * dotCols + x]
                if (g == 0.toByte()) continue
                if (rng.nextFloat() > prob) continue
                val lift = 1 + (rng.nextFloat() * 2f).toInt()
                val jitter = (rng.nextFloat() * 5).toInt() - 2
                val ny = max(0, y - lift)
                val nx = (x + jitter).coerceIn(0, dotCols - 1)
                if (grid[ny * dotCols + nx] == 0.toByte()) {
                    grid[ny * dotCols + nx] = g
                    grid[y * dotCols + x] = 0
                }
            }
        }
    }

    // Falling pass, bottom-up, alternating horizontal scan so piles don't
    // lean. Grains at the bottom row stay for the floor drain.
    private fun fall(frame: Long) {
        for (y in dotRows - 2 downTo 0) {
            val leftFirst = frame % 2L == 0L
            val xs = if (leftFirst) 0 until dotCols else (dotCols - 1) downTo 0
            for (x in xs) {
                val g = grid[y * dotCols + x]
                if (g == 0.toByte()) continue
                if (grid[(y + 1) * dotCols + x] == 0.toByte()) {
                    grid[(y + 1) * dotCols + x] = g
                    grid[y * dotCols + x] = 0
                    continue
                }
                val first = if (rng.nextFloat() < 0.5f) 1 else -1
                for (dx in listOf(first, -first)) {
                    val nx = x + dx
                    if (nx !in 0 until dotCols) continue
                    if (grid[(y + 1) * dotCols + nx] == 0.toByte()) {
                        grid[(y + 1) * dotCols + nx] = g
                        grid[y * dotCols + x] = 0
                        break
                    }
                }
            }
        }
    }

    // Floor: bottom-row grains drift off so long sessions never pack solid.
    private fun drainFloor() {
        for (x in 0 until dotCols) {
            if (grid[(dotRows - 1) * dotCols + x] != 0.toByte() && rng.nextFloat() < 0.04f) {
                grid[(dotRows - 1) * dotCols + x] = 0
            }
        }
    }

    private fun fillFraction(): Float {
        if (grid.isEmpty()) return 0f
        var fill = 0
        for (g in grid) if (g != 0.toByte()) fill++
        return fill.toFloat() / grid.size
    }

    // Every grain goes ballistic; the grid re-derives from particle
    // positions each tick while any fly.
    private fun startExplosion() {
        particles.clear()
        for (y in 0 until dotRows) {
            val depth = y.toFloat() / max(1, dotRows - 1)
            for (x in 0 until dotCols) {
                val g = grid[y * dotCols + x]
                if (g == 0.toByte()) continue
                grid[y * dotCols + x] = 0
                particles += Particle(
                    x = x.toDouble(),
                    y = y.toDouble(),
                    vx = (rng.nextDouble() - 0.5) * 8.0,
                    vy = -(2.0 + rng.nextDouble() * 5.0 + depth * 2.0),
                    tier = g,
                )
            }
        }
        explosionTtl = 80
    }

    private fun tickExplosion() {
        grid.fill(0)
        val live = ArrayList<Particle>(particles.size)
        for (p in particles) {
            p.vy += 0.50
            p.vx *= 0.985
            p.x += p.vx
            p.y += p.vy
            val ix = p.x.toInt()
            val iy = p.y.toInt()
            if (iy < 0 || iy >= dotRows || ix < 0 || ix >= dotCols) continue
            grid[iy * dotCols + ix] = p.tier
            live += p
        }
        particles.clear()
        particles.addAll(live)
        if (explosionTtl > 0) explosionTtl--
        if (particles.isEmpty()) explosionTtl = 0
    }

    private companion object {
        /** cliamp analyzes DefaultSpectrumBands for sand. */
        const val BANDS = 10
    }
}
