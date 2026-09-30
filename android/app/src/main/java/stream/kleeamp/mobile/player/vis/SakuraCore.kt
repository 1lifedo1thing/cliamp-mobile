package stream.kleeamp.mobile.player.vis

import kotlin.math.PI
import kotlin.math.sin

/**
 * Cherry-blossom petals, ported from cliamp's renderSakura
 * (`ui/vis_sakura.go`): a fixed population of braille-silhouette petals
 * drifting down with per-petal fall speed and lateral sway. Energy sets how
 * many petals fly (12 quiet, up to 28 loud). Closed-form in frame: no stored
 * state, fully deterministic.
 *
 * Large shapes fall slower (close), small ones faster (distant); petals
 * scroll through an off-screen buffer for smooth entry and exit.
 */
object SakuraCore {

    /** Petal silhouettes as (row, col) dot offsets, exactly like cliamp. */
    val shapes: List<List<Pair<Int, Int>>> = listOf(
        // Large - 6 dots, wide teardrop.
        listOf(0 to 1, 1 to 0, 1 to 1, 1 to 2, 2 to 0, 2 to 1),
        listOf(0 to 1, 1 to 0, 1 to 1, 1 to 2, 2 to 1, 2 to 2),
        listOf(0 to 1, 0 to 2, 1 to 0, 1 to 1, 1 to 2, 2 to 1),
        // Medium - 4 dots.
        listOf(0 to 1, 1 to 0, 1 to 1, 2 to 0),
        listOf(0 to 0, 1 to 0, 1 to 1, 2 to 1),
        listOf(0 to 0, 0 to 1, 1 to 1, 2 to 1),
        // Small - 2-3 dots, distant.
        listOf(0 to 0, 1 to 1),
        listOf(0 to 1, 1 to 0),
        listOf(0 to 0, 0 to 1, 1 to 0),
    )

    /** Petal count: 12 quiet, up to 28 loud. */
    fun petalCount(avgEnergy: Float): Int = 12 + (avgEnergy.coerceIn(0f, 1f) * 16).toInt()

    /**
     * Dots of petal [p] at [frame] on a [dotCols]x[dotRows] grid, each as
     * (x, y). Dots outside the grid are clipped by the caller, like cliamp.
     */
    fun dots(p: Int, frame: Long, dotCols: Int, dotRows: Int): List<Pair<Int, Int>> {
        val seed = p.toLong() * 104729L + 7919L
        val shapeIdx = ((seed * 4391L) % shapes.size).toInt()
        val shape = shapes[shapeIdx]
        val fallSpeed = if (shapeIdx >= 6) 2 else 1
        val baseX = VisMath.spreadSeed(seed, dotCols)
        val wrapH = dotRows + 10
        val baseY = VisMath.spreadSeed(seed * 3037L, wrapH.coerceAtLeast(1))
        val y = ((baseY + frame * fallSpeed / 8) % wrapH) - 5
        val swayPhase = (seed % 1000).toDouble() / 1000.0 * 2 * PI
        val x = baseX + (sin(frame * 0.015 + swayPhase) * 3.0).toInt()
        return shape.map { (dr, dc) -> (x + dc) to (y + dr).toInt() }
    }
}
