package stream.cliamp.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.validateListenBrainzToken

sealed interface ScrobbleProbe {
    data object Idle : ScrobbleProbe
    data object Running : ScrobbleProbe
    data class Ok(val user: String) : ScrobbleProbe
    data class Failed(val reason: String) : ScrobbleProbe
}

/**
 * The ListenBrainz setup wizard: the one field, then a probe against the live
 * service before anything is written. The caller saves [buildSaveToken] once
 * the probe is [ScrobbleProbe.Ok], so a typo fails here rather than silently
 * at the next counted play.
 */
class ScrobbleWizardViewModel(
    existingToken: String?,
) : ViewModel() {
    /** The token the wizard started from; blank means a fresh add. */
    val seedToken: String = existingToken.orEmpty()

    data class UiState(
        val token: String = "",
        val probe: ScrobbleProbe = ScrobbleProbe.Idle,
    )

    sealed interface Event {
        data class SetToken(val value: String) : Event
        data object Test : Event
    }

    private val token = MutableStateFlow(seedToken)
    private val probe = MutableStateFlow<ScrobbleProbe>(ScrobbleProbe.Idle)

    val state: StateFlow<UiState> = combine(token, probe) { t, p ->
        UiState(token = t, probe = p)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiState(token = seedToken),
    )

    fun onEvent(e: Event) {
        when (e) {
            is Event.SetToken -> {
                token.value = e.value.trim()
                probe.value = ScrobbleProbe.Idle
            }
            Event.Test -> viewModelScope.launch { runProbe() }
        }
    }

    /** The token SAVE writes once ListenBrainz has answered. Null until then. */
    fun buildSaveToken(): String? =
        if (probe.value is ScrobbleProbe.Ok) token.value else null

    private suspend fun runProbe() {
        val snapshot = token.value
        if (snapshot.isBlank()) {
            probe.value = ScrobbleProbe.Failed("paste a token first")
            return
        }
        probe.value = ScrobbleProbe.Running
        val t = snapshot.trim()
        token.value = t
        probe.value = validateListenBrainzToken(t).fold(
            onSuccess = { ScrobbleProbe.Ok(it) },
            onFailure = { ScrobbleProbe.Failed(it.message ?: "could not reach listenbrainz") },
        )
    }
}
