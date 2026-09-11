package stream.cliamp.mobile.ui.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.validateListenBrainzToken
import stream.cliamp.mobile.ui.components.BackChip
import stream.cliamp.mobile.ui.components.CliampTextField
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.MechKey
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

private sealed interface ScrobbleProbe {
    data object Idle : ScrobbleProbe
    data object Running : ScrobbleProbe
    data class Ok(val user: String) : ScrobbleProbe
    data class Failed(val reason: String) : ScrobbleProbe
}

/**
 * The ListenBrainz setup wizard, cut to the provider wizard's pattern:
 * intro, the one field, then a probe against the live service before
 * anything is written. The save key stays disabled until ListenBrainz has
 * actually answered, so a typo fails here rather than silently at the next
 * counted play.
 */
@Composable
fun ScrobbleWizard(
    existingToken: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var token by remember(existingToken) { mutableStateOf(existingToken) }
    var probe by remember { mutableStateOf<ScrobbleProbe>(ScrobbleProbe.Idle) }

    fun runProbe() {
        if (token.isBlank()) {
            probe = ScrobbleProbe.Failed("paste a token first")
            return
        }
        probe = ScrobbleProbe.Running
        scope.launch {
            val t = token.trim()
            token = t
            probe = validateListenBrainzToken(t).fold(
                onSuccess = { ScrobbleProbe.Ok(it) },
                onFailure = { ScrobbleProbe.Failed(it.message ?: "could not reach listenbrainz") },
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
                if (existingToken.isBlank()) "ADD SCROBBLER" else "EDIT",
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
                Mono("ListenBrainz", CliampType.screenTitle, p.ink)
                Mono(
                    "counted plays scrobble to your listenbrainz profile.",
                    CliampType.body, p.inkSecondary,
                )
                Mono(
                    "the user token lives in your profile settings on listenbrainz.org.",
                    CliampType.body, p.inkSecondary,
                )
            }
            HairlineDivider(region = true)

            TokenField(
                value = token,
                autoFocus = existingToken.isBlank(),
                onValue = {
                    token = it.trim()
                    probe = ScrobbleProbe.Idle
                },
                onDone = { focus.clearFocus(); runProbe() },
            )

            Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 14.dp)) {
                when (val s = probe) {
                    ScrobbleProbe.Idle -> Mono(
                        "nothing is saved until listenbrainz answers.",
                        CliampType.meta, p.inkFaint,
                    )
                    ScrobbleProbe.Running -> Mono("asking listenbrainz…", CliampType.rowSecondary, p.amber)
                    is ScrobbleProbe.Ok -> Mono("connected · ${s.user}", CliampType.rowSecondary, p.accent)
                    is ScrobbleProbe.Failed -> Mono(s.reason, CliampType.rowSecondary, p.destructiveInk)
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
                    enabled = probe != ScrobbleProbe.Running,
                ) { Mono("TEST", CliampType.chip) }
                MechKey(
                    onClick = {
                        if (probe is ScrobbleProbe.Ok) onSave(token)
                    },
                    modifier = Modifier.weight(1f),
                    height = 52.dp,
                    filled = true,
                    enabled = probe is ScrobbleProbe.Ok,
                ) { Mono("SAVE", CliampType.chip) }
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
            Mono("user token", CliampType.rowSecondary, if (focused) p.accent else p.inkTertiary)
        }
        CliampTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            placeholder = "token",
            textStyle = CliampType.trackTitleSmall,
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
