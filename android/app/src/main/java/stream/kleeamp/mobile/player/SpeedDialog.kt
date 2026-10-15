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
import kotlin.math.roundToInt
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.MechSlider
import stream.kleeamp.mobile.chrome.MenuSheetShell
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/** Every speed the sheet offers as quick chips, slowest first. */
internal val SpeedSteps = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Slider granularity: 0.05 steps across the 0.25–2.0 range. */
internal fun roundSpeed(v: Float): Float =
    ((v * 20f).roundToInt() / 20f).coerceIn(0.25f, 2f)

/**
 * Playback speed sheet: a fine slider on top, the classic preset chips
 * below. Picks apply live and persist under the playing section, so
 * podcasts keep podcast pace while radio stays 1x.
 */
@Composable
internal fun SpeedDialog(
    current: Float,
    sectionLabel: String,
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
                    Mono(
                        "currently ${speedLabel(current)}" +
                            if (sectionLabel.isNotBlank()) " · remembers ${sectionLabel}" else "",
                        KleeampType.rowSecondary, p.inkTertiary, maxLines = 1,
                    )
                }
            }
        },
    ) {
        MechSlider(
            value = current,
            onValueChange = { onPick(roundSpeed(it)) },
            range = 0.25f..2f,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
        // Eight chips never fit one sheet row; two rows of four do.
        SpeedSteps.chunked(4).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                for (step in row) {
                    Chip(
                        speedLabel(step),
                        kotlin.math.abs(step - current) < 0.01f,
                        onClick = { onPick(step); onDismiss() },
                    )
                }
            }
        }
    }
}
