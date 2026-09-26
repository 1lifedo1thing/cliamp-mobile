package stream.kleeamp.mobile.podcasts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.common.stateInUi
import stream.kleeamp.mobile.prefs.Prefs
import stream.kleeamp.mobile.model.Station

class PodcastShowViewModel(
    private val podcasts: PodcastRepository,
    private val prefs: Prefs,
    private val downloads: DownloadStore,
) : ViewModel() {
    data class UiState(
        val dlStates: Map<String, DownloadState>,
        val dlEntries: Map<String, DownloadEntry>,
        val autoDownload: Boolean,
        val showState: ShowState,
        val progress: Map<String, EpisodeProgress>,
        val subscriptions: List<PodcastShow>,
        val favorites: Set<String>,
    )

    sealed interface Event {
        data class ToggleSubscription(val show: PodcastShow) : Event
        data class ToggleFavorite(val station: Station) : Event
        data object RefreshShow : Event
        data class MarkCompleted(val station: Station) : Event
        data class ClearProgress(val station: Station) : Event
        data class Download(val station: Station) : Event
        data class CancelDownload(val url: String) : Event
        data class RemoveDownload(val url: String) : Event
        data object AutoDownload : Event
    }

    /** Show feed plus the sets rows read: subscriptions and favourited episode urls. */
    private data class ShowBits(
        val show: ShowState,
        val progress: Map<String, EpisodeProgress>,
        val subscriptions: List<PodcastShow>,
        val favorites: Set<String>,
    )

    val state: StateFlow<UiState> = combine(
        combine(downloads.states, downloads.entries, prefs.autoDownload) { a, b, c ->
            Triple(a, b, c)
        },
        combine(
            podcasts.show,
            podcasts.progress,
            podcasts.subscriptions,
            prefs.favorites,
        ) { show, progress, subscriptions, favorites ->
            ShowBits(show, progress, subscriptions, favorites.mapTo(HashSet()) { it.url })
        },
    ) { x, y ->
        UiState(
            dlStates = x.first,
            dlEntries = x.second,
            autoDownload = x.third,
            showState = y.show,
            progress = y.progress,
            subscriptions = y.subscriptions,
            favorites = y.favorites,
        )
    }.stateInUi(
        viewModelScope,
        UiState(
            dlStates = downloads.states.value,
            dlEntries = downloads.entries.value,
            autoDownload = false,
            showState = podcasts.show.value,
            progress = emptyMap(),
            subscriptions = emptyList(),
            favorites = emptySet(),
        ),
    )

    fun onEvent(e: Event) {
        when (e) {
            is Event.ToggleSubscription -> viewModelScope.launch {
                podcasts.toggleSubscription(e.show)
            }
            is Event.ToggleFavorite -> viewModelScope.launch { prefs.toggleFavorite(e.station) }
            is Event.RefreshShow -> podcasts.refreshShow()
            is Event.MarkCompleted -> viewModelScope.launch {
                podcasts.markCompleted(e.station)
            }
            is Event.ClearProgress -> viewModelScope.launch {
                podcasts.clearProgress(e.station)
            }
            is Event.Download -> downloads.download(e.station)
            is Event.CancelDownload -> downloads.cancel(e.url)
            is Event.RemoveDownload -> downloads.remove(e.url)
            is Event.AutoDownload -> {
                val s = state.value
                val show = s.showState.show ?: return
                val subscribed = s.subscriptions.any { it.feedUrl == show.feedUrl }
                if (subscribed && s.autoDownload && s.showState.episodes.isNotEmpty()) {
                    downloads.autoDownload(
                        show,
                        s.showState.episodes,
                        s.progress.filterValues { it.completed }.keys,
                    )
                }
            }
        }
    }
}
