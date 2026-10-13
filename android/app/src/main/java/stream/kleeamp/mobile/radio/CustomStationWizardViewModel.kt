package stream.kleeamp.mobile.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.net.Http

sealed interface CustomProbe {
    data object Idle : CustomProbe
    data object Running : CustomProbe
    data class Ok(val detail: String) : CustomProbe
    data class Failed(val reason: String) : CustomProbe
}

/**
 * The custom-station wizard: name + stream url, then a probe against the
 * live stream before anything is written. Nothing is saved until the stream
 * has actually answered, the same rule the provider and listenbrainz
 * wizards follow, so a typo fails here rather than as a dead row.
 */
class CustomStationWizardViewModel : ViewModel() {
    data class UiState(
        val name: String = "",
        val url: String = "",
        val probe: CustomProbe = CustomProbe.Idle,
    )

    sealed interface Event {
        data class SetName(val value: String) : Event
        data class SetUrl(val value: String) : Event
        data object Test : Event
    }

    private val name = MutableStateFlow("")
    private val url = MutableStateFlow("")
    private val probe = MutableStateFlow<CustomProbe>(CustomProbe.Idle)

    val state: StateFlow<UiState> = combine(name, url, probe) { n, u, p ->
        UiState(name = n, url = u, probe = p)
    }.stateInUi(viewModelScope, UiState())

    fun onEvent(e: Event) {
        when (e) {
            is Event.SetName -> {
                // The name is cosmetic and never re-probes: keep a good
                // probe so renaming alone never forces another round trip.
                name.value = e.value
            }
            is Event.SetUrl -> {
                url.value = e.value
                probe.value = CustomProbe.Idle
            }
            Event.Test -> viewModelScope.launch { runProbe() }
        }
    }

    /** The station SAVE writes once the probe has answered. Null until then. */
    fun buildSaveStation(): Station? {
        if (probe.value !is CustomProbe.Ok) return null
        return customStation(name.value, url.value)
    }

    private suspend fun runProbe() {
        val station = customStation(name.value, url.value)
        if (station == null) {
            probe.value = CustomProbe.Failed("enter a stream url like https://…")
            return
        }
        probe.value = CustomProbe.Running
        probe.value = probeCustomStream(station.url).fold(
            onSuccess = { CustomProbe.Ok(it) },
            onFailure = { CustomProbe.Failed(it.message ?: "could not reach the stream") },
        )
    }
}

/**
 * The wizard's TEST: opens [url] just long enough to read the response
 * headers, then closes without touching the endless body. Returns what the
 * headers say (icy name, content type) or "reachable" for a bare 200.
 * Pure network, safe to call on IO; errors stay short and URL-free.
 */
@Suppress("SwallowedException")
suspend fun probeCustomStream(url: String): Result<String> = runCatching {
    withContext(Dispatchers.IO) {
        Http.call(url, mapOf("Icy-MetaData" to "1")).use { r ->
            check(r.isSuccessful) { "the stream answered HTTP ${r.code}" }
            val icy = r.header("icy-name")?.trim().orEmpty()
            val contentType = r.header("Content-Type")?.substringBefore(";")?.trim().orEmpty()
            listOf(icy, contentType).filter { it.isNotBlank() }
                .joinToString(" · ").ifBlank { "reachable" }
        }
    }
}
