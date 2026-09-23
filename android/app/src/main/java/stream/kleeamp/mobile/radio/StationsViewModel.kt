package stream.kleeamp.mobile.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.model.Station

class StationsViewModel(
    private val repository: RadioRepository,
    private val prefs: Prefs,
) : ViewModel() {
    data class UiState(
        val cliamp: List<Station>,
        val cliampError: String?,
        val custom: List<Station>,
        val directory: DirectoryState,
        val directoryStats: DirectoryStats?,
        val tags: List<NameCount>,
        val countries: List<CountryCount>,
    )

    sealed interface Event {
        data object NextPage : Event
        data object RefreshCliamp : Event
        data class LoadDirectory(val query: DirectoryQuery, val reset: Boolean = true) : Event
        data class AddCustom(val station: Station) : Event
        data class RemoveCustom(val station: Station) : Event
        data class ToggleFavorite(val station: Station) : Event
    }

    private data class CatalogState(
        val cliamp: List<Station>,
        val cliampError: String?,
        val custom: List<Station>,
        val directory: DirectoryState,
        val directoryStats: DirectoryStats?,
    )

    private data class FacetState(
        val tags: List<NameCount>,
        val countries: List<CountryCount>,
    )

    val state: StateFlow<UiState> = combine(
        combine(
            repository.cliamp,
            repository.cliampError,
            prefs.custom,
            repository.directory,
            repository.directoryStats,
            ::CatalogState,
        ),
        combine(
            repository.tags,
            repository.countries,
            ::FacetState,
        ),
    ) { catalog, facets ->
        UiState(
            cliamp = catalog.cliamp,
            cliampError = catalog.cliampError,
            custom = catalog.custom,
            directory = catalog.directory,
            directoryStats = catalog.directoryStats,
            tags = facets.tags,
            countries = facets.countries,
        )
    }.stateInUi(
        viewModelScope,
        UiState(
            cliamp = repository.cliamp.value,
            cliampError = repository.cliampError.value,
            custom = emptyList(),
            directory = repository.directory.value,
            directoryStats = repository.directoryStats.value,
            tags = repository.tags.value,
            countries = emptyList(),
        ),
    )

    fun onEvent(e: Event) {
        when (e) {
            is Event.NextPage -> repository.nextPage()
            is Event.RefreshCliamp -> repository.refreshCliamp()
            is Event.LoadDirectory -> repository.loadDirectory(e.query, e.reset)
            is Event.AddCustom -> viewModelScope.launch {
                prefs.addCustom(e.station)
            }
            is Event.RemoveCustom -> viewModelScope.launch {
                prefs.removeCustom(e.station)
            }
            is Event.ToggleFavorite -> viewModelScope.launch {
                prefs.toggleFavorite(e.station)
            }
        }
    }
}
