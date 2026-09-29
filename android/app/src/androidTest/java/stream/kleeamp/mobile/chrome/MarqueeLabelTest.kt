package stream.kleeamp.mobile.chrome

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt
import stream.kleeamp.mobile.theme.KleeampTheme
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * The marquee must visibly travel while overflowing: LTR titles glide
 * left, RTL titles glide right (mirrored), past the initial hold.
 * Catches a dead scroll (offset pinned) and wrong-direction travel.
 */
@RunWith(AndroidJUnit4::class)
class MarqueeLabelTest {
    @get:Rule val compose = createComposeRule()

    private fun show(text: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KleeampTheme(haptics = false) {
                // Narrow lane so even modest titles overflow and loop.
                Box(Modifier.width(200.dp)) {
                    MarqueeLabel(
                        text = text,
                        style = KleeampType.rowPrimary,
                        color = LocalPalette.current.ink,
                    )
                }
            }
        }
        // Let measure + layout land (widths resolve, hold has not elapsed).
        compose.mainClock.advanceTimeBy(100)
    }

    private fun textX(text: String): Float =
        compose.onAllNodesWithText(text, substring = false)[0]
            .fetchSemanticsNode().positionInRoot.x

    private fun copyCount(text: String): Int =
        compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().size

    @Test fun overflowEngagesLoopStrip() {
        val title = "Unchained Melody by Roger Williams and His Singing Strings Orchestra"
        show(title)
        compose.mainClock.advanceTimeBy(500)
        assertTrue(
            "overflow never engaged (single copy rendered)",
            copyCount(title) == 2,
        )
    }

    @Test fun ltrPixelsTravel() {
        val title = "Unchained Melody by Roger Williams and His Singing Strings Orchestra"
        show(title)
        compose.mainClock.advanceTimeBy(1300)
        val before = compose.onRoot().captureToImage().asAndroidBitmap()
        var same = 0
        var different = 0
        repeat(4) { i ->
            compose.mainClock.advanceTimeBy(1500)
            val now = compose.onRoot().captureToImage().asAndroidBitmap()
            if (now.sameAs(before)) same++ else different++
        }
        assertTrue(
            "marquee pixels never changed across 6s (same=$same different=$different)",
            different > 0,
        )
    }
    @Test fun probeInfiniteTransitionMoves() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KleeampTheme(haptics = false) {
                Box(Modifier.width(200.dp)) {
                    val t = rememberInfiniteTransition(label = "probe")
                    val v by t.animateFloat(
                        0f,
                        -500f,
                        infiniteRepeatable(tween(2000), RepeatMode.Restart),
                        label = "v",
                    )
                    androidx.compose.material3.Text(
                        text = "probe",
                        style = TextStyle(fontSize = 15.sp),
                        modifier = Modifier.offset { IntOffset(v.roundToInt(), 0) },
                    )
                }
            }
        }
        val n0 = compose.onAllNodesWithText("probe", substring = false)[0]
            .fetchSemanticsNode().positionInRoot.x
        compose.mainClock.advanceTimeBy(1000)
        val n1 = compose.onAllNodesWithText("probe", substring = false)[0]
            .fetchSemanticsNode().positionInRoot.x
        assertTrue("bare infiniteTransition frozen in test env (n0=$n0 n1=$n1)", n1 < n0)
    }

    @Test fun rtlOverflowGlidesRight() {
        val title = "موزیک شاد و بسیار طولانی برای آزمایش حرکت متن در پخش کننده"
        show(title)
        val x0 = textX(title)
        compose.mainClock.advanceTimeBy(1200)
        compose.mainClock.advanceTimeBy(2000)
        val x1 = textX(title)
        assertTrue("RTL marquee did not glide right (x0=$x0 x1=$x1)", x1 > x0 + 50f)
    }
}
