package stream.kleeamp.mobile.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.theme.LocalPalette
import kotlin.math.roundToInt

/**
 * How many of [blocks] squares read as done for [fraction]. Negative
 * fractions (unknown length) fill none; the row's byte readout covers that.
 * Pure so the fill math is unit-tested on the JVM.
 */
internal fun blocksFilled(fraction: Float, blocks: Int): Int =
    if (blocks <= 0) 0
    else (fraction * blocks).roundToInt().coerceIn(0, blocks)

/**
 * Fetch progress as chunky squares, not a spinner: filled accent blocks for
 * done, hairline ones for the rest. Matches the meter blocks elsewhere.
 */
@Composable
fun DownloadBlocks(
    fraction: Float,
    blocks: Int = 8,
    modifier: Modifier = Modifier,
    filled: Color = LocalPalette.current.accent,
    empty: Color = LocalPalette.current.chipBorder,
) {
    val filledCount = blocksFilled(fraction, blocks)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(blocks) { i ->
            Box(
                Modifier
                    .size(5.dp)
                    .background(if (i < filledCount) filled else empty),
            )
        }
    }
}
