package stream.kleeamp.mobile.player.vis

/**
 * Twinkling particle field, mirroring cliamp's renderScatter: dot density
 * per band follows the squared energy with a gravity bias settling
 * particles near the bottom.
 */
object ScatterCore {

    /**
     * Whether the dot at ([col], [row]) of a [rows]-tall field is lit for
     * [level] at [frame].
     */
    fun lit(band: Int, col: Int, row: Int, rows: Int, frame: Long, level: Float): Boolean {
        if (rows <= 1) return false
        val heightFactor = 0.5f + 0.5f * row / (rows - 1f)
        return VisMath.scatterHash(band, row, col, frame) < level * level * heightFactor
    }
}
