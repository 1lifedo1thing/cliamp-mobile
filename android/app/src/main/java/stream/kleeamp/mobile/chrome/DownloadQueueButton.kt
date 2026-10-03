package stream.kleeamp.mobile.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * The floating fetch key: a plain accent circle with the download glyph
 * and a count badge, nothing else. It rides while the queue is non-empty
 * and pops the downloading sheet; the badge names how many rows wait
 * inside.
 */
@Composable
fun DownloadQueueButton(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    AnimatedVisibility(
        visible = count > 0,
        enter = fadeIn(tween(250)) + scaleIn(tween(250), initialScale = 0.7f),
        exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.7f),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .size(54.dp)
                .semantics { role = Role.Button }
                .microPress(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(p.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(KleeampIcons.Download, "open downloads", Modifier.size(20.dp), tint = p.onAccent)
            }
            Mono(
                if (count > 99) "99" else count.toString(),
                KleeampType.chip,
                p.accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .border(1.dp, p.accent, CircleShape)
                    .background(p.ground, CircleShape)
                    .sizeIn(minWidth = 20.dp, minHeight = 20.dp)
                    .padding(horizontal = 4.dp)
                    .wrapContentSize(Alignment.Center),
                maxLines = 1,
            )
        }
    }
}
