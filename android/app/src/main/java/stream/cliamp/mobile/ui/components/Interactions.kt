package stream.cliamp.mobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import stream.cliamp.mobile.ui.theme.LocalHapticsEnabled

/**
 * The app's feel layer for flat controls: a quick springing scale-down with a
 * slight dim while pressed, and a light confirmation tick on release, all
 * without the Material ripple the concept's flat faces disown.
 *
 * Pairs with [MechKey]'s travel for the moments that are small enough to be
 * pressed rather than "operated" - tab items, chips, toggles, tiles, the
 * corner icons. The scale animates with the same stiffness [MechKey] uses, so
 * a row of controls all press with the same weight.
 */
@Composable
fun Modifier.microPress(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val hf = LocalHapticFeedback.current
    val hapticsOn = enabled && LocalHapticsEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.955f else 1f,
        animationSpec = spring(stiffness = 900f, dampingRatio = 0.7f),
        label = "pressScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.82f else 1f,
        animationSpec = tween(90),
        label = "pressAlpha",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }.clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = {
            if (hapticsOn) hf.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
    )
}