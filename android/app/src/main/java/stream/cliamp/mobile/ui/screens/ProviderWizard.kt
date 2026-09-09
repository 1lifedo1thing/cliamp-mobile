package stream.cliamp.mobile.ui.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.provider.FieldKeyboard
import stream.cliamp.mobile.data.provider.FieldSpec
import stream.cliamp.mobile.data.provider.ProviderAccount
import stream.cliamp.mobile.data.provider.ProviderIdentity
import stream.cliamp.mobile.data.provider.ProviderSpec
import stream.cliamp.mobile.data.provider.SubsonicClient
import stream.cliamp.mobile.ui.components.BackChip
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.CliampTextField
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.MechKey
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

private sealed interface Probe {
    data object Idle : Probe
    data object Running : Probe
    data class Ok(val identity: ProviderIdentity) : Probe
    data class Failed(val reason: String) : Probe
}

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
    spec: ProviderSpec,
    existing: ProviderAccount?,
    onCancel: () -> Unit,
    onSave: (ProviderAccount) -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()

    val values = remember(spec.key, existing?.id) {
        mutableStateOf(
            buildMap {
                spec.fields.forEach { put(it.key, it.default) }
                spec.picker?.let { put(it.key, it.default) }
                existing?.values?.forEach { (k, v) -> put(k, v) }
            }
        )
    }
    var probe by remember { mutableStateOf<Probe>(Probe.Idle) }
    val focus = LocalFocusManager.current

    val visible = spec.visibleFields(values.value)
    val missing = spec.missingRequired(values.value)

    fun set(key: String, v: String) {
        values.value = values.value.toMutableMap().apply { put(key, v) }
        probe = Probe.Idle
    }

    fun runProbe() {
        val crossField = spec.extraValidate?.invoke(values.value)
        if (crossField != null) { probe = Probe.Failed(crossField); return }
        if (missing.isNotEmpty()) {
            probe = Probe.Failed("fill in " + missing.joinToString(", ") { it.label.lowercase() })
            return
        }
        probe = Probe.Running
        scope.launch {
            val normalised = values.value.toMutableMap().apply {
                this["url"]?.let { put("url", SubsonicClient.normalise(it)) }
            }
            values.value = normalised
            probe = spec.validate(normalised).fold(
                onSuccess = { identity ->
                    // What the probe worked out for itself - an SSH host key,
                    // the music folders it found - goes back into the form so
                    // that SAVE writes it with everything else.
                    if (identity.values.isNotEmpty()) {
                        values.value = values.value + identity.values
                    }
                    Probe.Ok(identity)
                },
                onFailure = { Probe.Failed(it.message ?: "could not reach the server") },
            )
        }
    }

    Column(Modifier.fillMaxSize().background(p.ground).statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackChip(onClick = onCancel)
            // QueueBar floats over this corner on every screen
            Mono(
                if (existing == null) "ADD PROVIDER" else "EDIT",
                CliampType.sectionLabel,
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
                Mono(spec.name, CliampType.screenTitle, p.ink)
                spec.intro.forEach { Mono(it, CliampType.body, p.inkSecondary) }
            }
            HairlineDivider(region = true)

            visible.forEachIndexed { i, field ->
                FieldRow(
                    field = field,
                    value = values.value[field.key].orEmpty(),
                    last = i == visible.lastIndex,
                    // the first field takes focus so the wizard is typeable on
                    // arrival instead of needing a tap first
                    autoFocus = i == 0 && existing == null && field.lines == 1,
                    onValue = { set(field.key, it) },
                    onNext = { focus.moveFocus(FocusDirection.Next) },
                    onDone = { focus.clearFocus(); runProbe() },
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
                        Chip(o.label, values.value[picker.key] == o.value, onClick = { set(picker.key, o.value) })
                    }
                }
            }

            Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 14.dp)) {
                when (val s = probe) {
                    Probe.Idle -> Mono(
                        "nothing is saved until the server answers.",
                        CliampType.meta, p.inkFaint,
                    )
                    Probe.Running -> Mono("reaching the server…", CliampType.rowSecondary, p.amber)
                    is Probe.Ok -> Mono(
                        listOf("connected", s.identity.name, s.identity.detail)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        CliampType.rowSecondary, p.accent,
                    )
                    is Probe.Failed -> Mono(s.reason, CliampType.rowSecondary, p.destructiveInk)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = Gutter),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                MechKey(
                    onClick = { runProbe() },
                    modifier = Modifier.weight(1f),
                    height = 52.dp,
                    enabled = probe != Probe.Running,
                ) { Mono("TEST", CliampType.chip) }
                MechKey(
                    onClick = {
                        val ok = probe as? Probe.Ok ?: return@MechKey
                        onSave(
                            ProviderAccount(
                                id = existing?.id ?: "${spec.key}:${System.currentTimeMillis()}",
                                providerKey = spec.key,
                                label = ok.identity.name.ifBlank { spec.name },
                                values = values.value,
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                    height = 52.dp,
                    filled = true,
                    enabled = probe is Probe.Ok,
                ) { Mono("SAVE", CliampType.chip) }
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
            Mono(field.label, CliampType.rowSecondary, if (focused) p.accent else p.inkTertiary)
            if (!field.required) Mono("optional", CliampType.meta, p.inkFaint)
        }
        CliampTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            placeholder = field.help.ifBlank { field.label.lowercase() },
            textStyle = CliampType.trackTitleSmall,
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
