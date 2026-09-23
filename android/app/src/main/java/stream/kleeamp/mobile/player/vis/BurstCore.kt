package stream.kleeamp.mobile.player.vis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Beat-driven burst for the kleeamp visualizer: a bass onset fires a ring, a
 * spark shower and a screen shake. Coordinates are panel fractions, so the
 * same burst reads at any size.
 */
class BurstCore(private val random: Random = Random.Default) {

    class Spark(
        var x: Float,
        var y: Float,
        var px: Float,
        var py: Float,
        var vx: Float,
        var vy: Float,
        var life: Float,
        val maxLife: Float,
    ) {
        /** On screen and still burning. */
        fun isAlive(): Boolean =
            life > 0f && x >= -0.1f && x <= 1.1f && y >= -0.1f && y <= 1.1f
    }

    val sparks = ArrayList<Spark>()

    var shockRadius = 0f
        private set
    var shockLife = 0f
        private set
    var flash = 0f
        private set
    var shake = 0f
        private set

    private var smoothBass = 0f
    private var cooldown = 0f

    fun push(bands: FloatArray, dt: Float) {
        val step = dt.coerceIn(0f, 0.1f)
        val bass = bassOf(bands)

        if (cooldown > 0f) cooldown -= step
        val rise = bass - smoothBass
        smoothBass += (bass - smoothBass) * (step * 5f).coerceAtMost(1f)
        flash = (flash - step * 2.2f).coerceAtLeast(0f)
        shake = (shake - step * 3.2f).coerceAtLeast(0f)

        if (cooldown <= 0f && bass > ONSET_FLOOR && rise > ONSET_RISE) {
            val intensity = bass.coerceIn(0f, 1f)
            cooldown = COOLDOWN
            flash = (flash + 0.45f + intensity * 0.55f).coerceAtMost(1f)
            shake = (shake + 0.5f + intensity * 0.5f).coerceAtMost(1f)
            shockRadius = 0.04f
            shockLife = 1f
            val count = (12 + intensity * 26f).toInt()
            repeat(count) {
                val angle = random.nextDouble(0.0, 2 * PI)
                val speed = 0.35 + random.nextDouble() * 0.95
                val life = 0.45f + random.nextFloat() * 0.7f
                val x = 0.5f + (random.nextFloat() - 0.5f) * 0.06f
                val y = 0.5f + (random.nextFloat() - 0.5f) * 0.06f
                sparks += Spark(
                    x = x,
                    y = y,
                    px = x,
                    py = y,
                    vx = (cos(angle) * speed).toFloat(),
                    vy = (sin(angle) * speed * 0.75).toFloat(),
                    life = life,
                    maxLife = life,
                )
            }
        }

        shockRadius += 0.85f * step
        shockLife = (shockLife - step * 1.4f).coerceAtLeast(0f)

        val iterator = sparks.iterator()
        while (iterator.hasNext()) {
            val spark = iterator.next()
            spark.px = spark.x
            spark.py = spark.y
            spark.x += spark.vx * step
            spark.y += spark.vy * step
            spark.vy += GRAVITY * step
            spark.life -= step
            if (!spark.isAlive()) {
                iterator.remove()
            }
        }
    }

    fun reset() {
        sparks.clear()
        shockRadius = 0f
        shockLife = 0f
        flash = 0f
        shake = 0f
        smoothBass = 0f
        cooldown = 0f
    }

    private fun bassOf(bands: FloatArray): Float {
        if (bands.isEmpty()) return 0f
        val count = (bands.size / 8).coerceAtLeast(1)
        var sum = 0f
        for (i in 0 until count) sum += bands[i]
        return sum / count
    }

    private companion object {
        const val COOLDOWN = 0.22f
        const val ONSET_FLOOR = 0.16f
        const val ONSET_RISE = 0.05f
        const val GRAVITY = 0.35f
    }
}
