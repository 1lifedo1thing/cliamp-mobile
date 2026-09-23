package stream.kleeamp.mobile.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.common.stateInUi

class ProvidersPaneViewModel(
    private val providers: ProviderStore,
) : ViewModel() {
    data class UiState(
        val providers: List<ProviderAccount> = emptyList(),
    )

    sealed interface Event {
        data class Remove(val account: ProviderAccount) : Event
    }

    val state: StateFlow<UiState> = combine(
        providers.accounts,
        flowOf(Unit),
    ) { accounts, _ ->
        UiState(providers = accounts)
    }.stateInUi(
        viewModelScope,
        UiState(),
    )

    fun onEvent(e: Event) {
        when (e) {
            is Event.Remove -> viewModelScope.launch { providers.remove(e.account.id) }
        }
    }
}
