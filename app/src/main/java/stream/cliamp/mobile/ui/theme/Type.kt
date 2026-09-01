package stream.cliamp.mobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import stream.cliamp.mobile.R

/** One monospace face. No second family anywhere. */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private val trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun mono(
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    tracking: Double = 0.0,
    lineHeight: Double = 1.35,
) = TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = weight,
    fontSize = size.sp,
    letterSpacing = tracking.em,
    lineHeight = (size * lineHeight).sp,
    lineHeightStyle = trim,
)

/** Sizes lifted verbatim from the concept's type scale. */
@Immutable
object CliampType {
    val clock = mono(76, FontWeight.Medium, -0.04, 1.0)
    val screenTitle = mono(24, FontWeight.Bold, -0.02, 1.15)
    val trackTitle = mono(28, FontWeight.Bold, -0.015, 1.14)
    val trackTitleCompact = mono(19, FontWeight.Bold, -0.012, 1.2)
    val trackTitleSmall = mono(16, FontWeight.Bold, -0.01, 1.2)
    val rowPrimary = mono(15, FontWeight.Normal, 0.0, 1.3)
    val rowPrimaryMedium = mono(15, FontWeight.Medium, 0.0, 1.3)
    val rowPrimarySmall = mono(14, FontWeight.Normal, 0.0, 1.3)
    val rowSecondary = mono(12, FontWeight.Normal, 0.0, 1.35)
    val meta = mono(11, FontWeight.Normal, 0.0, 1.35)
    val body = mono(13, FontWeight.Normal, 0.0, 1.6)
    val sectionLabel = mono(11, FontWeight.Normal, 0.14, 1.3)
    val chip = mono(11, FontWeight.Normal, 0.08, 1.2)
    val keyCap = mono(12, FontWeight.Normal, 0.06, 1.2)
    val tabLabel = mono(10, FontWeight.Normal, 0.10, 1.2)
    val statusBar = mono(13, FontWeight.Normal, 0.0, 1.2)
    val nowPlayingLabel = mono(11, FontWeight.Normal, 0.16, 1.2)
    val bigNumber = mono(34, FontWeight.Bold, -0.02, 1.05)
}
