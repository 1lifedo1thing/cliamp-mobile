package stream.kleeamp.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.MenuSheetShell
import stream.kleeamp.mobile.chrome.SheetOptionRow
import stream.kleeamp.mobile.chrome.clock
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/** Every speed the sheet offers, slowest first so the list scans naturally. */
internal val SpeedSteps = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/**
 * Playback speed sheet on the menu chrome: one tap picks a speed outright.
 * Cycling the key through eight blind steps took five taps to reach 0.5x
 * from normal; the list shows the whole ladder with the current step
 * checked, like the playlist picker's ticked rows.
 */
@Composable
internal fun SpeedDialog(
    current: Float,
    onPick: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    MenuSheetShell(
        onDismiss = onDismiss,
        header = {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(KleeampShape.small))
                        .background(p.panel),
                    contentAlignment = Alignment.Center,
                ) {
                    Mono(
                        speedLabel(current),
                        KleeampType.trackTitleCompact,
                        if (current != 1f) p.accent else p.ink,
                        maxLines = 1,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Mono("Playback Speed", KleeampType.trackTitleCompact, p.ink, maxLines = 1)
                    Mono("currently ${speedLabel(current)}", KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
                }
            }
        },
    ) {
        for (step in SpeedSteps) {
            SheetOptionRow(
                label = speedLabel(step),
                selected = kotlin.math.abs(step - current) < 0.01f,
                onClick = { onPick(step); onDismiss() },
            )
        }
    }
}
