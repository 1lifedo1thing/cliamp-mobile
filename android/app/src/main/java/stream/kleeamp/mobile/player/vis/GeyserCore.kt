package stream.kleeamp.mobile.player.vis

import kotlin.random.Random

/**
 * Particle fountain, mirroring cliamp's geyserDriver: sustained loudness
 * holds a mist column, bass transients launch vertical jets, and every
 * particle arcs back down under gravity with lateral spray. Tiers follow
 * the band that produced each particle.
 */
class GeyserCore(seed: Long = 0xFEED5EED) {
    data class Drop(var x: Float, var y: Float, var vx: Float, var vy: Float, val tier: Int, var life: Int)

    private val rng = Random(seed)
    private val drops = ArrayList<Drop>()
    private var prevBass = 0f

    fun drops(): List<Drop> = drops.toList()

    /**
     * Fixed virtual canvas: positions live in 0..100 x 0..160 so the sim
     * never depends on pixels; renderers scale up.
     */
    fun push(bands: FloatArray) {
        push(bands, 100f, 160f)
    }

    fun push(bands: FloatArray, width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        val bass = VisMath.sampleLinear(bands, 0.5f)
        val avg = if (bands.isEmpty()) 0f else bands.average().toFloat()
        // Steady mist column while loud.
        val mist = (avg * 6).toInt()
        repeat(mist) {
            drops += Drop(
                x = width * (0.42f + rng.nextFloat() * 0.16f),
                y = height,
                vx = rng.nextFloat() - 0.5f,
                vy = -(height * (0.008f + rng.nextFloat() * 0.012f)),
                tier = 1,
                life = 40 + rng.nextInt(40),
            )
        }
        // Bass transient launches a jet.
        if (bass - prevBass > 0.3f) {
            repeat(24) {
                val band = rng.nextInt(bands.size.coerceAtLeast(1))
                drops += Drop(
                    x = width * (0.46f + rng.nextFloat() * 0.08f),
                    y = height,
                    vx = rng.nextFloat() * 2f - 1f,
                    vy = -(height * (0.02f + rng.nextFloat() * 0.03f)),
                    tier = (band * 3 / bands.size.coerceAtLeast(1) + 1).coerceIn(1, 3),
                    life = 50 + rng.nextInt(40),
                )
            }
        }
        prevBass = bass
        val it = drops.iterator()
        while (it.hasNext()) {
            val d = it.next()
            d.life--
            if (d.life <= 0 || d.y > height) {
                it.remove()
                continue
            }
            d.x += d.vx
            d.y += d.vy
            d.vy += height * 0.0006f
        }
        if (drops.size > 400) {
            repeat(drops.size - 400) { drops.removeAt(0) }
        }
    }

    fun settle() {
        drops.clear()
        prevBass = 0f
    }
}
