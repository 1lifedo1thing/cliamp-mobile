package stream.kleeamp.mobile.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.KleeampTextField
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.chrome.clock
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

private val sleepOptions: List<Int?> = listOf(null, 15, 30, 45, 60)

private fun sleepLabel(minutes: Int?): String = when (minutes) {
    null -> "off"
    else -> "$minutes min"
}

/**
 * Sleep timer sheet: pauses playback when the countdown ends. The timer
 * lives in the player connection (checked on the 2 Hz sync tick), so it
 * fires with the screen off; it is deliberately not persisted.
 */
@Composable
internal fun SleepDialog(
    sleepAtMs: Long?,
    onPick: (Int?) -> Unit,
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
            Mono("SLEEP TIMER", KleeampType.tabLabel, p.inkTertiary, maxLines = 1)
            sleepAtMs?.let { at ->
                val left = (at - System.currentTimeMillis()).coerceAtLeast(0)
                Mono(
                    "pauses in " + clock(left),
                    KleeampType.meta,
                    p.accent,
                    Modifier.padding(bottom = 6.dp),
                    maxLines = 1,
                )
            }
            val activeMinutes = sleepAtMs?.let { (it - System.currentTimeMillis()) / 60_000L }
            for (option in sleepOptions) {
                val selected = when (option) {
                    null -> sleepAtMs == null
                    else -> activeMinutes != null && activeMinutes in (option - 5)..(option + 1)
                }
                Mono(
                    sleepLabel(option),
                    KleeampType.rowPrimary,
                    if (selected) p.accent else p.ink,
                    Modifier
                        .microPress { onPick(option); onDismiss() }
                        .padding(horizontal = Gutter, vertical = 9.dp),
                    maxLines = 1,
                )
            }
            var customOpen by remember { mutableStateOf(false) }
            var customText by remember { mutableStateOf("") }
            if (!customOpen) {
                Mono(
                    "custom…",
                    KleeampType.rowPrimary,
                    p.ink,
                    Modifier
                        .microPress { customOpen = true }
                        .padding(horizontal = Gutter, vertical = 9.dp),
                    maxLines = 1,
                )
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Gutter, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KleeampTextField(
                        value = customText,
                        onValueChange = { customText = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.weight(1f),
                        placeholder = "minutes",
                        keyboardType = KeyboardType.Number,
                    )
                    Mono(
                        "SET",
                        KleeampType.chip,
                        p.accent,
                        Modifier
                            .microPress {
                                customText.toIntOrNull()?.takeIf { it in 1..999 }?.let {
                                    onPick(it)
                                    onDismiss()
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
