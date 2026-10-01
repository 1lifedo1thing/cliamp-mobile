package stream.kleeamp.mobile.player.vis

import kotlin.math.sin

/**
 * KLEEAMP pixel letters dissolving with energy, ported from cliamp's
 * renderLogo (`ui/vis_logo.go`) with our own word: 5x7 glyphs in cliamp's
 * style, each letter wired to its own frequency band, dots gated by energy
 * (loud fills solid, silence dissolves to scattered pixels), with a
 * traveling wave and bounce keeping quiet passages alive.
 */
object LogoCore {

    /** 5x7 glyphs, bit 4 (0x10) is the leftmost pixel, in cliamp's style. */
    val glyphs = arrayOf(
        intArrayOf(0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11), // K
        intArrayOf(0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F), // L
        intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F), // E
        intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F), // E
        intArrayOf(0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11), // A
        intArrayOf(0x11, 0x1B, 0x15, 0x11, 0x11, 0x11, 0x11), // M
        intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10), // P
    )

    const val LETTER_W = 5
    const val LETTER_H = 7
    const val LETTERS = 7
    const val GAP = 2
    const val TOTAL_W = LETTERS * LETTER_W + (LETTERS - 1) * GAP // 47

    /** Band each letter listens to, spread across the spectrum like cliamp. */
    val letterBand = intArrayOf(0, 2, 3, 5, 6, 8, 9)

    /** Integer dot scale that fits the word into [dotCols], at least 1. */
    fun scaleX(dotCols: Int): Int = maxOf(1, dotCols / TOTAL_W)

    /**
     * Column step between letter starts: glyph pitch plus the leftover
     * spread evenly so the word fills the panel edge to edge instead of
     * sitting narrow and centered.
     */
    fun columnStep(dotCols: Int): Int {
        val s = scaleX(dotCols)
        val extra = maxOf(0, (dotCols - TOTAL_W * s) / (LETTERS - 1))
        return (LETTER_W + GAP) * s + extra
    }

    /** Glyph pixel (px, py) of letter [li] set or not. */
    fun pixelSet(li: Int, px: Int, py: Int): Boolean {
        if (li !in 0 until LETTERS || px !in 0 until LETTER_W || py !in 0 until LETTER_H) return false
        return glyphs[li][py] and (0x10 shr px) != 0
    }

    /**
     * Fraction of glyph pixels lit: loud fills solid, silence dissolves.
     * Floor lifted above cliamp's 0.15 so the word stays readable at
     * moderate phone listening levels instead of dissolving away.
     */
    fun fill(energy: Float): Float = energy * energy * 0.75f + 0.28f

    /** Vertical bounce in dots for letter [li] at [frame]. */
    fun bounce(energy: Float, baseOffsetY: Int, li: Int, frame: Long): Int {
        val wave = sin(frame * 0.06 + li * 0.9) * 1.5
        return (energy * baseOffsetY * 0.3f + wave).toInt()
    }

    /** Whether a scaled glyph dot draws: energy gate over the dot hash. */
    fun dotKept(li: Int, px: Int, py: Int, frame: Long, fill: Float): Boolean =
        VisMath.scatterHash(li, py, px, frame) <= fill
}
