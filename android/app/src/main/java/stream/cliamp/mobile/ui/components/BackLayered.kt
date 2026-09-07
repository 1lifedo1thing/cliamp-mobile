package stream.cliamp.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import stream.cliamp.mobile.ui.theme.LocalPalette

/**
 * A single non-main page that overlays the page it was opened from and rides
 * the predictive back gesture to reveal it. Every secondary page in the app
 * wraps itself in one of these so the back behavior is consistent everywhere
 * without any per-screen boilerplate.
 *
 * [visible] gates composition: the page is only shown while this is true. When
 * it disappears (on back commit) the page beneath was already previewed during
 * the slide, so the transition is seamless. [onBack] pops the page; the host
 * owns the state. [onProgress] hands the signed back gesture progress (0..1
 * left, 0..-1 right) out so the caller can tint or scale the page behind it
 * while it previews during the glide.
 */
@Composable
fun BackPage(
    visible: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onProgress: (Float) -> Unit = {},
    content: @Composable () -> Unit,
) {
    if (!visible) return
    PredictiveBackSurface(
        enabled = enabled,
        onBack = onBack,
        onProgress = onProgress,
        modifier = modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().background(LocalPalette.current.ground)) {
            content()
        }
    }
}
