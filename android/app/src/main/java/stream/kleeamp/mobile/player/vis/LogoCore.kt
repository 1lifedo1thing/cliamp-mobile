package stream.kleeamp.mobile.player.vis

/**
 * KLEEAMP pixel letters dissolving with energy, mirroring cliamp's logo
 * mode (which spells CLIAMP the same way): each dot of the 5x7 glyphs
 * appears when its band is loud, with a bounce and wave keeping quiet
 * passages alive.
 */
object LogoCore {

    // 5x7 glyphs, bit 4 (0x10) is the leftmost pixel.
    private val glyphs = mapOf(
        'K' to intArrayOf(0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11),
        'L' to intArrayOf(0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F),
        'E' to intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F),
        'A' to intArrayOf(0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11),
        'M' to intArrayOf(0x11, 0x1B, 0x15, 0x11, 0x11, 0x11, 0x11),
        'P' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10),
    )

    const val WORD = "KLEEAMP"
    const val LETTER_W = 5
    const val LETTER_H = 7
    const val GAP = 2
    const val TOTAL_W = WORD.length * LETTER_W + (WORD.length - 1) * GAP

    /** Pixel (x, y) of the word on or off. */
    fun pixelOn(x: Int, y: Int): Boolean {
        val li = x / (LETTER_W + GAP)
        val glyph = WORD.getOrNull(li)?.let(glyphs::get)
        return y in 0 until LETTER_H &&
            x in 0 until TOTAL_W &&
            li in WORD.indices &&
            x % (LETTER_W + GAP) < LETTER_W &&
            glyph != null &&
            glyph[y] and (0x10 shr (x % (LETTER_W + GAP))) != 0
    }

    /**
     * Whether a lit pixel draws this frame: its band must be loud enough,
     * with bounce and wave offsets so quiet passages shimmer instead of
     * vanishing.
     */
    fun dotOn(x: Int, y: Int, level: Float, frame: Long): Boolean {
        if (!pixelOn(x, y)) return false
        val bounce = if ((frame / 8 + x) % 24 < 2) 1 else 0
        val wave = if ((frame / 6 + y) % 30 < 2) 1 else 0
        if (bounce == 1 || wave == 1) return true
        return VisMath.scatterHash(x, y, 7, frame / 10) < level
    }
}
