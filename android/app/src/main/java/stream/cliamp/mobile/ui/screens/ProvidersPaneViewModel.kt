package stream.cliamp.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.provider.ProviderAccount
import stream.cliamp.mobile.data.provider.ProviderStore

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
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiState(),
    )

    fun onEvent(e: Event) {
        when (e) {
            is Event.Remove -> viewModelScope.launch { providers.remove(e.account.id) }
        }
    }
}
