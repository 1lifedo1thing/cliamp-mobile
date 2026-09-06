package stream.cliamp.mobile.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import stream.cliamp.mobile.ui.theme.CliampPalette
import kotlin.math.hypot
import kotlin.random.Random

/**
 * A plate for anything that has no cover art.
 *
 * The striped plate this replaces was honest and, across a grid of twenty
 * tiles, extremely dull: every album that never shipped artwork looked like
 * every other one. These are still not pretending to be album art - no
 * lettering, no photographs, nothing that could be mistaken for the real
 * cover - but each thing gets its own.
 *
 * Two rules make it work. The figure is chosen by a hash of the item's own id,
 * so a given album keeps the same plate forever rather than reshuffling on
 * every recomposition, and every colour comes out of the palette, so the plates
 * re-theme with the rest of the app instead of sitting on top of it.
 *
 * Amber and red are not in the palette below on purpose: amber means "this
 * lives somewhere else" and red means destructive, and neither should turn up
 * as decoration.
 */
fun DrawScope.drawArtPlate(seed: String, p: CliampPalette) {
    val rng = Random(seed.hashCode())

    val ground = if (rng.nextBoolean()) p.artA else p.artB
    val alt = if (ground == p.artA) p.artB else p.artA
    val line = listOf(p.artBorder, p.hairlineRegion, p.frameBorder)[rng.nextInt(3)]
    // The accent appears on roughly half of the plates, and never as the whole
    // field - a wall of accent-coloured tiles is worse than a wall of grey.
    val mark = if (rng.nextBoolean()) p.accent else listOf(p.inkFaint, p.inkTertiary, p.accentWash)[rng.nextInt(3)]

    drawRect(color = ground)
    clipRect {
        when (rng.nextInt(6)) {
            0 -> stripes(rng, alt, line)
            1 -> bars(rng, alt, mark)
            2 -> rings(rng, line, mark)
            3 -> lattice(rng, line, mark)
            4 -> chevrons(rng, line, mark)
            5 -> blocks(rng, alt, line, mark)
        }
    }
}

/** The original plate, kept in the rotation with a varying angle and pitch. */
private fun DrawScope.stripes(rng: Random, alt: Color, line: Color) {
    val w = size.minDimension * (0.05f + rng.nextFloat() * 0.06f)
    val angle = listOf(-45f, 45f, 0f, 90f)[rng.nextInt(4)]
    rotate(degrees = angle, pivot = Offset(size.width / 2f, size.height / 2f)) {
        val diag = hypot(size.width, size.height)
        var x = size.width / 2f - diag
        var i = 0
        while (x < size.width / 2f + diag) {
            if (i % 2 == 0) {
                drawRect(
                    color = if (i % 4 == 0) alt else line,
                    topLeft = Offset(x, size.height / 2f - diag),
                    size = Size(w, diag * 2f),
                )
            }
            x += w
            i++
        }
    }
}

/** The app's own meter language, frozen: a column field of varying heights. */
private fun DrawScope.bars(rng: Random, alt: Color, mark: Color) {
    val n = 5 + rng.nextInt(7)
    val gap = size.width / n * 0.28f
    val w = (size.width - gap * (n - 1)) / n
    val hot = rng.nextInt(n)
    for (i in 0 until n) {
        val h = size.height * (0.18f + rng.nextFloat() * 0.72f)
        drawRect(
            color = if (i == hot) mark else alt,
            topLeft = Offset(i * (w + gap), size.height - h),
            size = Size(w, h),
        )
    }
}

private fun DrawScope.rings(rng: Random, line: Color, mark: Color) {
    val n = 3 + rng.nextInt(4)
    val cx = size.width * (0.25f + rng.nextFloat() * 0.5f)
    val cy = size.height * (0.25f + rng.nextFloat() * 0.5f)
    val step = size.minDimension / (n + 1).toFloat() * 0.9f
    val hot = rng.nextInt(n)
    for (i in 0 until n) {
        drawCircle(
            color = if (i == hot) mark else line,
            radius = step * (i + 1) / 2f,
            center = Offset(cx, cy),
            style = Stroke(width = size.minDimension * 0.035f),
        )
    }
}

private fun DrawScope.lattice(rng: Random, line: Color, mark: Color) {
    val n = 3 + rng.nextInt(3)
    val cell = size.width / n
    val stroke = size.minDimension * 0.02f
    for (i in 1 until n) {
        drawRect(color = line, topLeft = Offset(i * cell, 0f), size = Size(stroke, size.height))
        drawRect(color = line, topLeft = Offset(0f, i * cell), size = Size(size.width, stroke))
    }
    repeat(1 + rng.nextInt(3)) {
        val cx = rng.nextInt(n)
        val cy = rng.nextInt(n)
        drawRect(
            color = mark,
            topLeft = Offset(cx * cell + stroke, cy * cell + stroke),
            size = Size(cell - stroke * 2, cell - stroke * 2),
        )
    }
}

private fun DrawScope.chevrons(rng: Random, line: Color, mark: Color) {
    val n = 4 + rng.nextInt(4)
    val step = size.height / n
    val stroke = size.minDimension * 0.03f
    val flip = rng.nextBoolean()
    val hot = rng.nextInt(n)
    for (i in 0 until n) {
        val y = if (flip) size.height - i * step else i * step
        val c = if (i == hot) mark else line
        drawLine(c, Offset(0f, y), Offset(size.width / 2f, y - step * 0.6f), stroke)
        drawLine(c, Offset(size.width / 2f, y - step * 0.6f), Offset(size.width, y), stroke)
    }
}

private fun DrawScope.blocks(rng: Random, alt: Color, line: Color, mark: Color) {
    repeat(2 + rng.nextInt(3)) {
        val w = size.width * (0.25f + rng.nextFloat() * 0.5f)
        val h = size.height * (0.25f + rng.nextFloat() * 0.5f)
        drawRect(
            color = listOf(alt, line, mark)[rng.nextInt(3)],
            topLeft = Offset(
                (size.width - w) * rng.nextFloat(),
                (size.height - h) * rng.nextFloat(),
            ),
            size = Size(w, h),
        )
    }
}
