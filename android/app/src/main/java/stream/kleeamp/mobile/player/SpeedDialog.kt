package stream.kleeamp.mobile.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/** Every speed the dialog offers, slowest first so the list scans naturally. */
internal val SpeedSteps = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/**
 * Playback speed sheet: one tap picks a speed outright. Cycling the key
 * through eight blind steps took five taps to reach 0.5x from normal; the
 * list shows the whole ladder with the current step checked.
 */
@Composable
internal fun SpeedDialog(
    current: Float,
    onPick: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    BackHandler(onBack = onDismiss)
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .microPress(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.78f)
                .clip(RoundedCornerShape(KleeampShape.large))
                .background(p.panelRaised)
                .padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Mono("PLAYBACK SPEED", KleeampType.tabLabel, p.inkTertiary, maxLines = 1)
            for (step in SpeedSteps) {
                Mono(
                    speedLabel(step),
                    KleeampType.rowPrimary,
                    if (kotlin.math.abs(step - current) < 0.01f) p.accent else p.ink,
                    Modifier
                        .microPress { onPick(step); onDismiss() }
                        .padding(horizontal = Gutter, vertical = 9.dp),
                    maxLines = 1,
                )
            }
        }
    }
}
