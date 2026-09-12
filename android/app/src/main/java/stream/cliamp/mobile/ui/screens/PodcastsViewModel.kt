package stream.cliamp.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.CountryCount
import stream.cliamp.mobile.data.PodcastDirectoryState
import stream.cliamp.mobile.data.PodcastQuery
import stream.cliamp.mobile.data.PodcastRepository
import stream.cliamp.mobile.data.PodcastShow
import stream.cliamp.mobile.data.Prefs

class PodcastsViewModel(
    private val podcasts: PodcastRepository,
    private val prefs: Prefs,
    countries: StateFlow<List<CountryCount>>,
) : ViewModel() {
    data class UiState(
        val subsGrid: Boolean,
        val podDirectoryGrid: Boolean,
        val countries: List<CountryCount>,
        val directory: PodcastDirectoryState,
        val subscriptions: List<PodcastShow>,
    )

    sealed interface Event {
        data object ToggleSubsGrid : Event
        data object TogglePodDirectoryGrid : Event
        data object NextPage : Event
        data class Load(val query: PodcastQuery) : Event
        data class ToggleSubscription(val show: PodcastShow) : Event
    }

    val state: StateFlow<UiState> = combine(
        prefs.subsGrid,
        prefs.podDirectoryGrid,
        countries,
        podcasts.directory,
        podcasts.subscriptions,
    ) { subsGrid, podDirectoryGrid, countryList, directory, subscriptions ->
        UiState(
            subsGrid = subsGrid,
            podDirectoryGrid = podDirectoryGrid,
            countries = countryList,
            directory = directory,
            subscriptions = subscriptions,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiState(
            subsGrid = prefs.subsGrid.value,
            podDirectoryGrid = prefs.podDirectoryGrid.value,
            countries = emptyList(),
            directory = podcasts.directory.value,
            subscriptions = emptyList(),
        ),
    )

    fun onEvent(e: Event) {
        when (e) {
            is Event.ToggleSubsGrid -> viewModelScope.launch {
                prefs.setSubsGrid(!state.value.subsGrid)
            }
            is Event.TogglePodDirectoryGrid -> viewModelScope.launch {
                prefs.setPodDirectoryGrid(!state.value.podDirectoryGrid)
            }
            is Event.NextPage -> podcasts.nextPage()
            is Event.Load -> podcasts.load(e.query, reset = true)
            is Event.ToggleSubscription -> viewModelScope.launch {
                podcasts.toggleSubscription(e.show)
            }
        }
    }
}
