package stream.cliamp.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.provider.ProviderAccount
import stream.cliamp.mobile.data.provider.ProviderIdentity
import stream.cliamp.mobile.data.provider.ProviderSpec
import stream.cliamp.mobile.data.provider.SubsonicClient

sealed interface Probe {
    data object Idle : Probe
    data object Running : Probe
    data class Ok(val identity: ProviderIdentity) : Probe
    data class Failed(val reason: String) : Probe
}

/**
 * The add-provider wizard: the spec's values, then a probe against the live
 * server before anything is written. Nothing is saved until the server has
 * actually answered; the caller persists the account [buildSaveAccount]
 * returns once the probe is [Probe.Ok].
 */
class ProviderWizardViewModel(
    val spec: ProviderSpec,
    private val existing: ProviderAccount?,
) : ViewModel() {
    data class UiState(
        val values: Map<String, String> = emptyMap(),
        val probe: Probe = Probe.Idle,
    )

    sealed interface Event {
        data class SetValue(val key: String, val value: String) : Event
        data object Test : Event
    }

    /** True when adding; false when editing an existing account. */
    val isNew: Boolean = existing == null

    private fun initialValues(): Map<String, String> = buildMap {
        spec.fields.forEach { put(it.key, it.default) }
        spec.picker?.let { put(it.key, it.default) }
        existing?.values?.forEach { (k, v) -> put(k, v) }
    }

    private val values = MutableStateFlow(initialValues())
    private val probe = MutableStateFlow<Probe>(Probe.Idle)

    val state: StateFlow<UiState> = combine(values, probe) { v, p ->
        UiState(values = v, probe = p)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiState(values = initialValues()),
    )

    fun onEvent(e: Event) {
        when (e) {
            is Event.SetValue -> {
                values.value = values.value.toMutableMap().apply { put(e.key, e.value) }
                probe.value = Probe.Idle
            }
            Event.Test -> viewModelScope.launch { runProbe() }
        }
    }

    /** The account SAVE writes once the probe has answered. Null until then. */
    fun buildSaveAccount(): ProviderAccount? {
        val ok = probe.value as? Probe.Ok ?: return null
        return ProviderAccount(
            id = existing?.id ?: "${spec.key}:${System.currentTimeMillis()}",
            providerKey = spec.key,
            label = ok.identity.name.ifBlank { spec.name },
            values = values.value,
        )
    }

    private suspend fun runProbe() {
        val snapshot = values.value
        val crossField = spec.extraValidate?.invoke(snapshot)
        if (crossField != null) { probe.value = Probe.Failed(crossField); return }
        val missing = spec.missingRequired(snapshot)
        if (missing.isNotEmpty()) {
            probe.value = Probe.Failed("fill in " + missing.joinToString(", ") { it.label.lowercase() })
            return
        }
        probe.value = Probe.Running
        val normalised = snapshot.toMutableMap().apply {
            this["url"]?.let { put("url", SubsonicClient.normalise(it)) }
        }
        values.value = normalised
        probe.value = spec.validate(normalised).fold(
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
