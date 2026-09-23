package stream.kleeamp.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.KleeampTextField
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.HairlineDivider
import stream.kleeamp.mobile.chrome.MechKey
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * The ListenBrainz setup wizard, cut to the provider wizard's pattern:
 * intro, the one field, then a probe against the live service before
 * anything is written. The save key stays disabled until ListenBrainz has
 * actually answered, so a typo fails here rather than silently at the next
 * counted play.
 */
@Composable
fun ScrobbleWizard(
    vm: ScrobbleWizardViewModel,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    val p = LocalPalette.current
    val focus = LocalFocusManager.current

    val uiState by vm.state.collectAsState()

    Column(Modifier.fillMaxSize().background(p.ground).statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackChevron(onCancel)
            // QueueBar floats over this corner on every screen
            Mono(
                if (vm.seedToken.isBlank()) "ADD SCROBBLER" else "EDIT",
                KleeampType.sectionLabel,
                p.inkTertiary,
                Modifier.padding(end = 56.dp),
            )
        }
        HairlineDivider(region = true)

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.padding(horizontal = Gutter, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Mono("ListenBrainz", KleeampType.screenTitle, p.ink)
                Mono(
                    "counted plays scrobble to your listenbrainz profile.",
                    KleeampType.body, p.inkSecondary,
                )
                Mono(
                    "the user token lives in your profile settings on listenbrainz.org.",
                    KleeampType.body, p.inkSecondary,
                )
            }
            HairlineDivider(region = true)

            TokenField(
                value = uiState.token,
                autoFocus = vm.seedToken.isBlank(),
                onValue = { vm.onEvent(ScrobbleWizardViewModel.Event.SetToken(it)) },
                onDone = {
                    focus.clearFocus()
                    vm.onEvent(ScrobbleWizardViewModel.Event.Test)
                },
            )

            Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 14.dp)) {
                when (val s = uiState.probe) {
                    ScrobbleProbe.Idle -> Mono(
                        "nothing is saved until listenbrainz answers.",
                        KleeampType.meta, p.inkFaint,
                    )
                    ScrobbleProbe.Running -> Mono("asking listenbrainz…", KleeampType.rowSecondary, p.amber)
                    is ScrobbleProbe.Ok -> Mono("connected · ${s.user}", KleeampType.rowSecondary, p.accent)
                    is ScrobbleProbe.Failed -> Mono(s.reason, KleeampType.rowSecondary, p.destructiveInk)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = Gutter),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                MechKey(
                    onClick = { vm.onEvent(ScrobbleWizardViewModel.Event.Test) },
                    modifier = Modifier.weight(1f),
                    height = 52.dp,
                    enabled = uiState.probe != ScrobbleProbe.Running,
                ) { Mono("TEST", KleeampType.chip) }
                MechKey(
                    onClick = { vm.buildSaveToken()?.let(onSave) },
                    modifier = Modifier.weight(1f),
                    height = 52.dp,
                    filled = true,
                    enabled = uiState.probe is ScrobbleProbe.Ok,
                ) { Mono("SAVE", KleeampType.chip) }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** The one field, wearing the provider wizard's FieldRow styling exactly. */
@Composable
private fun TokenField(
    value: String,
    autoFocus: Boolean,
    onValue: (String) -> Unit,
    onDone: () -> Unit,
) {
    val p = LocalPalette.current
    var focused by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Mono("user token", KleeampType.rowSecondary, if (focused) p.accent else p.inkTertiary)
        }
        KleeampTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            placeholder = "token",
            textStyle = KleeampType.trackTitleSmall,
            secret = true,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
            autoFocus = autoFocus,
            onAction = onDone,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused) 2.dp else 1.dp)
                .background(if (focused) p.accent else p.hairline)
        )
    }
}
