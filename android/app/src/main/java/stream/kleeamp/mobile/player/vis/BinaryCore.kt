package stream.kleeamp.mobile.player.vis

/**
 * Streaming 0/1 columns, exactly cliamp's renderBinary (`ui/vis_binary.go`):
 * each column scrolls at an energy-proportional speed, louder bands show
 * more 1s, and 1s on hot bands glow bright.
 */
object BinaryCore {

    /** Scroll divisor for an energy level: hotter bands flow faster. */
    fun speed(energy: Float): Int = maxOf(1, 4 - (energy.coerceIn(0f, 1f) * 3).toInt())

    /**
     * Bit for ([band], [row], [col]) at [scroll] frames scrolled. The hash is
     * time-independent - the scroll offset creates the motion.
     */
    fun bit(band: Int, row: Int, col: Int, scroll: Int, energy: Float): Boolean {
        val h = VisMath.scatterHash(band, row + scroll, col, 0)
        return h < energy.coerceIn(0f, 1f) * 0.6f + 0.15f
    }

    /** Color tier for a bit at an energy level, like cliamp's tags. */
    fun tier(bit: Boolean, energy: Float): Int = when {
        bit && energy > 0.4f -> 2
        bit || energy > 0.3f -> 1
        else -> 0
    }
}
