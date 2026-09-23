package stream.kleeamp.mobile.library

import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.settings.Scrobbler
import stream.kleeamp.mobile.model.Station

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
    }.stateInUi(
        viewModelScope,
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
