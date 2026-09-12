package stream.cliamp.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.CountryCount
import stream.cliamp.mobile.data.DirectoryQuery
import stream.cliamp.mobile.data.DirectoryState
import stream.cliamp.mobile.data.DirectoryStats
import stream.cliamp.mobile.data.NameCount
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.Repository
import stream.cliamp.mobile.data.Station

class StationsViewModel(
    private val repository: Repository,
    private val prefs: Prefs,
) : ViewModel() {
    data class UiState(
        val cliampGrid: Boolean,
        val directoryGrid: Boolean,
        val customGrid: Boolean,
        val cliamp: List<Station>,
        val cliampError: String?,
        val custom: List<Station>,
        val directory: DirectoryState,
        val directoryStats: DirectoryStats?,
        val tags: List<NameCount>,
        val countries: List<CountryCount>,
    )

    sealed interface Event {
        data object ToggleCliampGrid : Event
        data object ToggleDirectoryGrid : Event
        data object ToggleCustomGrid : Event
        data object NextPage : Event
        data object RefreshCliamp : Event
        data class LoadDirectory(val query: DirectoryQuery, val reset: Boolean = true) : Event
        data class AddCustom(val station: Station) : Event
        data class RemoveCustom(val station: Station) : Event
        data class ToggleFavorite(val station: Station) : Event
    }

    private data class GridState(
        val cliampGrid: Boolean,
        val directoryGrid: Boolean,
        val customGrid: Boolean,
    )

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
            prefs.cliampGrid,
            prefs.directoryGrid,
            prefs.customGrid,
            ::GridState,
        ),
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
    ) { grids, catalog, facets ->
        UiState(
            cliampGrid = grids.cliampGrid,
            directoryGrid = grids.directoryGrid,
            customGrid = grids.customGrid,
            cliamp = catalog.cliamp,
            cliampError = catalog.cliampError,
            custom = catalog.custom,
            directory = catalog.directory,
            directoryStats = catalog.directoryStats,
            tags = facets.tags,
            countries = facets.countries,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiState(
            cliampGrid = prefs.cliampGrid.value,
            directoryGrid = prefs.directoryGrid.value,
            customGrid = prefs.customGrid.value,
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
            is Event.ToggleCliampGrid -> viewModelScope.launch {
                prefs.setCliampGrid(!state.value.cliampGrid)
            }
            is Event.ToggleDirectoryGrid -> viewModelScope.launch {
                prefs.setDirectoryGrid(!state.value.directoryGrid)
            }
            is Event.ToggleCustomGrid -> viewModelScope.launch {
                prefs.setCustomGrid(!state.value.customGrid)
            }
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
