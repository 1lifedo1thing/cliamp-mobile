package stream.cliamp.mobile.ui.screens

import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.LocalLibrary
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.Scrobbler
import stream.cliamp.mobile.data.Station

class SongInfoViewModel(
    val stationUrl: String,
    private val localLibrary: LocalLibrary,
    private val prefs: Prefs,
    private val scrobbler: Scrobbler,
) : ViewModel() {
    data class UiState(
        val songs: List<Station> = emptyList(),
        val favorites: List<Station> = emptyList(),
        val recent: List<Station> = emptyList(),
        val plays: Int = 0,
        val lastPlayedAt: Long = 0L,
        val favorite: Boolean = false,
    )

    sealed interface Event {
        data class ToggleFavorite(val station: Station) : Event
        data class DeleteLocal(val station: Station) : Event
    }

    val state: StateFlow<UiState> = combine(
        localLibrary.songs,
        prefs.favorites,
        prefs.history,
        scrobbler.statsFor(stationUrl),
    ) { songs, favs, rec, stat ->
        UiState(
            songs = songs,
            favorites = favs,
            recent = rec,
            plays = stat?.plays ?: 0,
            lastPlayedAt = stat?.lastPlayedAt ?: 0L,
            favorite = favs.any { it.url == stationUrl },
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiState(songs = localLibrary.songs.value),
    )

    fun deleteRequest(s: Station): PendingIntent? = localLibrary.deleteRequest(s)

    fun onEvent(e: Event) {
        when (e) {
            is Event.ToggleFavorite -> viewModelScope.launch { prefs.toggleFavorite(e.station) }
            is Event.DeleteLocal -> viewModelScope.launch {
                localLibrary.removeLocal(e.station)
                prefs.removeFavorite(e.station)
            }
        }
    }
}
