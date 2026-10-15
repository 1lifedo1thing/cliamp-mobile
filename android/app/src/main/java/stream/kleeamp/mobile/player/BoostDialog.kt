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
import stream.kleeamp.mobile.chrome.MechSlider
import stream.kleeamp.mobile.chrome.MenuSheetShell
import stream.kleeamp.mobile.prefs.MaxBoostDb
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * Volume-boost sheet: extra gain over the volume slider for quiet
 * recordings. Picks apply live; 0 dB means off.
 */
@Composable
internal fun BoostDialog(
    current: Int,
    sectionLabel: String,
    onPick: (Int) -> Unit,
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
                        if (current > 0) "+${current}dB" else "off",
                        KleeampType.trackTitleCompact,
                        if (current > 0) p.accent else p.ink,
                        maxLines = 1,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Mono("Volume Boost", KleeampType.trackTitleCompact, p.ink, maxLines = 1)
                    Mono(
                        "extra gain over the volume slider" +
                            if (sectionLabel.isNotBlank()) " · remembers $sectionLabel" else "",
                        KleeampType.rowSecondary, p.inkTertiary, maxLines = 1,
                    )
                }
            }
        },
    ) {
        MechSlider(
            value = current.toFloat(),
            onValueChange = { onPick(it.roundToInt()) },
            range = 0f..MaxBoostDb.toFloat(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
    }
}
