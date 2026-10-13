package stream.kleeamp.mobile.radio

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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.HairlineDivider
import stream.kleeamp.mobile.chrome.KleeampTextField
import stream.kleeamp.mobile.chrome.MechKey
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * The custom-station wizard, cut to the provider wizard's pattern: intro,
 * the two fields, then a probe against the live stream before anything is
 * written. The save key stays disabled until the stream has actually
 * answered, so a typo fails here rather than as a dead row. Saving pops
 * back to the stations list, where the new row shows with the rest.
 */
@Composable
fun CustomStationWizard(
    vm: CustomStationWizardViewModel,
    onCancel: () -> Unit,
    onSave: (Station) -> Unit,
) {
    val p = LocalPalette.current
    val focus = LocalFocusManager.current

    val uiState by vm.state.collectAsState()

    Column(Modifier.fillMaxSize().background(p.ground).statusBarsPadding().navigationBarsPadding()) {
        // Title row like every MainLayout page: chevron, then the title on
        // the left in the same 24sp face.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackChevron(onCancel)
            Spacer(Modifier.width(4.dp))
            Mono("Custom station", KleeampType.screenTitle, p.ink, maxLines = 1)
        }
        HairlineDivider(region = true)

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.padding(horizontal = Gutter, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Mono(
                    "a stream you add by hand, by its stream url.",
                    KleeampType.body, p.inkSecondary,
                )
                Mono(
                    "test reaches the stream before anything is written.",
                    KleeampType.body, p.inkSecondary,
                )
            }
            HairlineDivider(region = true)

            StationField(
                label = "name",
                optional = true,
                value = uiState.name,
                autoFocus = false,
                onValue = { vm.onEvent(CustomStationWizardViewModel.Event.SetName(it)) },
                placeholder = "name",
                keyboard = KeyboardType.Text,
                imeAction = ImeAction.Next,
                onAction = { focus.moveFocus(FocusDirection.Next) },
            )
            StationField(
                label = "stream url",
                optional = false,
                value = uiState.url,
                autoFocus = true,
                onValue = { vm.onEvent(CustomStationWizardViewModel.Event.SetUrl(it)) },
                placeholder = "https://…",
                keyboard = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                onAction = {
                    focus.clearFocus()
                    vm.onEvent(CustomStationWizardViewModel.Event.Test)
                },
            )

            Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 14.dp)) {
                when (val s = uiState.probe) {
                    CustomProbe.Idle -> Mono(
                        "nothing is saved until the stream answers.",
                        KleeampType.meta, p.inkFaint,
                    )
                    CustomProbe.Running -> Mono("reaching the stream…", KleeampType.rowSecondary, p.amber)
                    is CustomProbe.Ok -> Mono(
                        "reachable" + if (s.detail.isNotBlank() && s.detail != "reachable") " · ${s.detail}" else "",
                        KleeampType.rowSecondary, p.accent,
                    )
                    is CustomProbe.Failed -> Mono(s.reason, KleeampType.rowSecondary, p.destructiveInk)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = Gutter),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                MechKey(
                    onClick = { vm.onEvent(CustomStationWizardViewModel.Event.Test) },
                    modifier = Modifier.weight(1f),
                    height = 52.dp,
                    enabled = uiState.probe != CustomProbe.Running,
                ) { Mono("TEST", KleeampType.chip) }
                MechKey(
                    onClick = { vm.buildSaveStation()?.let(onSave) },
                    modifier = Modifier.weight(1f),
                    height = 52.dp,
                    filled = true,
                    enabled = uiState.probe is CustomProbe.Ok,
                ) { Mono("SAVE", KleeampType.chip) }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** One wizard field row: label (+ optional marker), entry, and the hairline
 * underline that accents while focused. Wears the provider wizard's
 * FieldRow styling exactly. */
@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
private fun StationField(
    label: String,
    optional: Boolean,
    value: String,
    autoFocus: Boolean,
    onValue: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardType,
    imeAction: ImeAction,
    onAction: () -> Unit,
) {
    val p = LocalPalette.current
    var focused by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Mono(label, KleeampType.rowSecondary, if (focused) p.accent else p.inkTertiary)
            if (optional) Mono("optional", KleeampType.meta, p.inkFaint)
        }
        KleeampTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            placeholder = placeholder,
            textStyle = KleeampType.trackTitleSmall,
            keyboardType = keyboard,
            imeAction = imeAction,
            autoFocus = autoFocus,
            onAction = onAction,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused) 2.dp else 1.dp)
                .background(if (focused) p.accent else p.hairline)
        )
    }
}
