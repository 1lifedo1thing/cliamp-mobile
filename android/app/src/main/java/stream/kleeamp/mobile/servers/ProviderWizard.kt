package stream.kleeamp.mobile.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.servers.DISPLAY_NAME_KEY
import stream.kleeamp.mobile.servers.FieldKeyboard
import stream.kleeamp.mobile.servers.FieldSpec
import stream.kleeamp.mobile.servers.ProviderAccount
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.KleeampTextField
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.HairlineDivider
import stream.kleeamp.mobile.chrome.MechKey
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * The add-provider wizard: intro, the spec's fields, then a probe against the
 * live server before anything is written.
 *
 * The order matters. cliamp desktop validates before saving for a reason: a
 * typo in a URL or password otherwise saves happily and only surfaces later as
 * an empty library, with nothing to point at. Here the save key stays disabled
 * until the server has actually answered.
 */
@Composable
fun ProviderWizard(
    vm: ProviderWizardViewModel,
    onCancel: () -> Unit,
    onSave: (ProviderAccount) -> Unit,
) {
    val p = LocalPalette.current
    val focus = LocalFocusManager.current

    val uiState by vm.state.collectAsState()
    val spec = vm.spec

    val visible = spec.visibleFields(uiState.values)

    Column(Modifier.fillMaxSize().background(p.ground).statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackChevron(onCancel)
            // QueueBar floats over this corner on every screen
            Mono(
                if (vm.isNew) "ADD PROVIDER" else "EDIT",
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
                Mono(spec.name, KleeampType.screenTitle, p.ink)
                spec.intro.forEach { Mono(it, KleeampType.body, p.inkSecondary) }
            }
            HairlineDivider(region = true)

            // The user's own name for this account, shown in lists instead of
            // the probed server name. Optional and app-only: blank keeps
            // exactly today's behavior, and it never re-probes.
            FieldRow(
                field = FieldSpec(
                    key = DISPLAY_NAME_KEY,
                    label = "display name",
                    required = false,
                ),
                value = uiState.values[DISPLAY_NAME_KEY].orEmpty(),
                last = false,
                autoFocus = false,
                onValue = { vm.onEvent(ProviderWizardViewModel.Event.SetValue(DISPLAY_NAME_KEY, it)) },
                onNext = { focus.moveFocus(FocusDirection.Next) },
                onDone = {
                    focus.clearFocus()
                    vm.onEvent(ProviderWizardViewModel.Event.Test)
                },
            )

            visible.forEachIndexed { i, field ->
                FieldRow(
                    field = field,
                    value = uiState.values[field.key].orEmpty(),
                    last = i == visible.lastIndex,
                    // the first field takes focus so the wizard is typeable on
                    // arrival instead of needing a tap first
                    autoFocus = i == 0 && vm.isNew && field.lines == 1,
                    onValue = { vm.onEvent(ProviderWizardViewModel.Event.SetValue(field.key, it)) },
                    onNext = { focus.moveFocus(FocusDirection.Next) },
                    onDone = {
                        focus.clearFocus()
                        vm.onEvent(ProviderWizardViewModel.Event.Test)
                    },
                )
            }

            spec.picker?.let { picker ->
                SectionLabel(picker.label)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = Gutter, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    picker.options.forEach { o ->
                        Chip(
                            o.label,
                            uiState.values[picker.key] == o.value,
                            onClick = { vm.onEvent(ProviderWizardViewModel.Event.SetValue(picker.key, o.value)) },
                        )
                    }
                }
            }

            Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 14.dp)) {
                when (val s = uiState.probe) {
                    Probe.Idle -> Mono(
                        "nothing is saved until the server answers.",
                        KleeampType.meta, p.inkFaint,
                    )
                    Probe.Running -> Mono("reaching the server…", KleeampType.rowSecondary, p.amber)
                    is Probe.Ok -> Mono(
                        listOf("connected", s.identity.name, s.identity.detail)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        KleeampType.rowSecondary, p.accent,
                    )
                    is Probe.Failed -> Mono(s.reason, KleeampType.rowSecondary, p.destructiveInk)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = Gutter),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                MechKey(
                    onClick = { vm.onEvent(ProviderWizardViewModel.Event.Test) },
                    modifier = Modifier.weight(1f),
                    height = 52.dp,
                    enabled = uiState.probe != Probe.Running,
                ) { Mono("TEST", KleeampType.chip) }
                MechKey(
                    onClick = { vm.buildSaveAccount()?.let(onSave) },
                    modifier = Modifier.weight(1f),
                    height = 52.dp,
                    filled = true,
                    enabled = uiState.probe is Probe.Ok,
                ) { Mono("SAVE", KleeampType.chip) }
            }
            Spacer(Modifier.height(18.dp))
        }

    }
}

/**
 * A real text field, not a painted one.
 *
 * The app ships a custom on-screen keyboard for the command bar, and reusing it
 * here was a mistake: it cannot offer a URL keyboard, it bypasses autofill and
 * password managers, it ignores the user's own IME, language and clipboard, and
 * it is invisible to accessibility services. BasicTextField keeps the concept's
 * hairline styling while letting the platform own text entry.
 */
@Composable
private fun FieldRow(
    field: FieldSpec,
    value: String,
    last: Boolean,
    autoFocus: Boolean,
    onValue: (String) -> Unit,
    onNext: () -> Unit,
    onDone: () -> Unit,
) {
    val p = LocalPalette.current
    var focused by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Mono(field.label, KleeampType.rowSecondary, if (focused) p.accent else p.inkTertiary)
            if (!field.required) Mono("optional", KleeampType.meta, p.inkFaint)
        }
        KleeampTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            placeholder = field.help.ifBlank { field.label.lowercase() },
            textStyle = KleeampType.trackTitleSmall,
            secret = field.secret,
            keyboardType = when (field.keyboard) {
                FieldKeyboard.Url -> KeyboardType.Uri
                FieldKeyboard.Number -> KeyboardType.Number
                FieldKeyboard.Text -> KeyboardType.Text
            },
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
            autoFocus = autoFocus,
            lines = field.lines,
            onAction = { if (last) onDone() else onNext() },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused) 2.dp else 1.dp)
                .background(if (focused) p.accent else p.hairline)
        )
    }
}
