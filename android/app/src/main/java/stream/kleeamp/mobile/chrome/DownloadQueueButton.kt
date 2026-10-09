package stream.kleeamp.mobile.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * The floating fetch key: a secondary-surface circle with the download
 * glyph and a count badge, nothing else. It rides while the queue is
 * non-empty and pops the downloading sheet; the badge names how many rows
 * wait inside. While a show Download-all is still resolving its feed with
 * nothing queued yet, the key breathes instead of wearing a number that
 * has nothing behind it.
 */
@Composable
fun DownloadQueueButton(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    resolving: Boolean = false,
) {
    val p = LocalPalette.current
    AnimatedVisibility(
        visible = count > 0 || resolving,
        enter = fadeIn(tween(250)) + scaleIn(tween(250), initialScale = 0.7f),
        exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.7f),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .size(54.dp)
                .semantics { role = Role.Button }
                // Nothing queued yet while resolving: the tap waits for
                // the number instead of flashing an empty sheet.
                .microPress(onClick = { if (count > 0) onClick() }),
            contentAlignment = Alignment.Center,
        ) {
            if (count > 0) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(p.panel)
                        .border(1.dp, p.chipBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(KleeampIcons.Download, "open downloads", Modifier.size(20.dp), tint = p.accent)
                }
                Mono(
                    if (count > 99) "99" else count.toString(),
                    KleeampType.chip,
                    p.accent,
                    // Tucked inside the key's own corner, over the circle's
                    // uniform fill: hanging it off the edge tangled the ring
                    // with whatever row scrolled underneath.
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .border(1.dp, p.accent, CircleShape)
                        .background(p.panel, CircleShape)
                        .sizeIn(minWidth = 20.dp, minHeight = 20.dp)
                        .padding(horizontal = 4.dp)
                        .wrapContentSize(Alignment.Center),
                    maxLines = 1,
                )
            } else {
                // Resolving with nothing queued yet: breathe freely, no
                // badge; the number pops in when the episodes land.
                val pulse by rememberInfiniteTransition(label = "resolving").animateFloat(
                    initialValue = 1f,
                    targetValue = 0.45f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                    label = "resolvingAlpha",
                )
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(p.panel)
                        .border(1.dp, p.chipBorder, CircleShape)
                        .alpha(pulse),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(KleeampIcons.Download, "resolving downloads", Modifier.size(20.dp), tint = p.accent)
                }
            }
        }
    }
}
