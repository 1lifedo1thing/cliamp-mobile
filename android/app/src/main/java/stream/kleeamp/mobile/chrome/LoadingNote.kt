package stream.kleeamp.mobile.chrome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono
import kotlin.math.ceil

/**
 * cliamp's braille-dot spinner, one frame every 100ms. Pure so the frame
 * math is unit-tested on the JVM.
 */
internal val SpinnerFrames = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

internal fun spinnerFrame(elapsedMs: Long): String =
    SpinnerFrames[(elapsedMs / 100).mod(SpinnerFrames.size)]

/**
 * A loading row the cliamp way: braille spinner plus label, instead of a
 * static ellipsis note.
 */
@Composable
fun LoadingNote(
    label: String,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val clock by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = SpinnerFrames.size.toFloat(),
        animationSpec = infiniteRepeatable(tween(100 * SpinnerFrames.size, easing = LinearEasing)),
        label = "spinnerFrame",
    )
    Box(modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 18.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono(spinnerFrame((clock * 100).toLong()), KleeampType.rowSecondary, p.accent, maxLines = 1)
            Mono(label, KleeampType.rowSecondary, p.inkFaint, maxLines = 1)
        }
    }
}

/**
 * Refetch key: a circular arrow that spins while [loading] and plays one
 * full turn per tap otherwise, settling back upright when the fetch lands.
 */
@Composable
fun RefreshIcon(
    loading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val spin = remember { Animatable(0f) }
    LaunchedEffect(loading) {
        if (loading) {
            while (true) {
                spin.animateTo(spin.value + 360f, tween(900, easing = LinearEasing))
            }
        } else {
            val upright = ceil(spin.value / 360f) * 360f
            if (upright != spin.value) spin.animateTo(upright, tween(250))
        }
    }
    Icon(
        KleeampIcons.Refresh,
        "refresh",
        modifier
            .clip(CircleShape)
            .microPress {
                if (!loading) {
                    scope.launch { spin.animateTo(spin.value + 360f, tween(650)) }
                }
                onRefresh()
            }
            .graphicsLayer { rotationZ = spin.value }
            .padding(7.dp)
            .size(14.dp),
        tint = p.inkTertiary,
    )
}
