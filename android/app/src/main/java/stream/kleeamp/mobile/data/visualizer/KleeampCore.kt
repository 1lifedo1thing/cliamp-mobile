package stream.kleeamp.mobile.data.visualizer

/**
 * The kleeamp visualizer's state: a mirrored spectrum field, the six bars of
 * the kleeamp mark, a bass envelope for the glow, and the burst on top.
 */
class KleeampCore(val columns: Int) {

    private val field = MeterCore(columns)
    private val bars = MeterCore(BAR_COUNT)

    val burst = BurstCore()

    val levels get() = field.levels
    val barLevels get() = bars.levels

    var bass = 0f
        private set

    fun push(bands: FloatArray, dt: Float) {
        field.push(bands)
        bars.push(bands)
        burst.push(bands, dt)

        val low = bassOf(bands)
        bass = if (low > bass) low else bass + (low - bass) * (dt * 3f).coerceAtMost(1f)
    }

    /** Rest state for pause: settled field and bars, no burst, no glow. */
    fun settle() {
        field.settle()
        bars.settle()
        burst.reset()
        bass = 0f
    }

    companion object {
        const val BAR_COUNT = 6

        fun bassOf(bands: FloatArray): Float {
            if (bands.isEmpty()) return 0f
            val count = (bands.size / 8).coerceAtLeast(1)
            var sum = 0f
            for (i in 0 until count) sum += bands[i]
            return sum / count
        }
    }
}
