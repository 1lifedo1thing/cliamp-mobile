package stream.kleeamp.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.data.PlaylistSort
import stream.kleeamp.mobile.data.provider.IndexState
import stream.kleeamp.mobile.data.provider.ProviderAccount
import stream.kleeamp.mobile.data.provider.ProviderAlbum
import stream.kleeamp.mobile.data.provider.ProviderArtist
import stream.kleeamp.mobile.data.provider.ProviderTrack
import stream.kleeamp.mobile.data.provider.browseClient

/** Where in the provider's own hierarchy we are. */
sealed interface Node {
    data object Home : Node
    data class Artist(val id: String, val name: String) : Node
    data class Album(val id: String, val name: String, val artist: String) : Node
}

/**
 * Browses one provider's library: albums, artists, drilling into an album's
 * track list. Owns the browse stack, the sort order, the fetched lists and
 * the fetch itself; navigation and playback stay with the caller.
 *
 * Sorting wears exactly the local-songs chips (title / artist / album /
 * recently added). The chips pick both the server query and the client-side
 * order: title and album read the A-Z index, artist reads the artist index,
 * and recently added reads the newest index in server order.
 */
class ProviderBrowseViewModel(
    val account: ProviderAccount,
) : ViewModel() {
    data class UiState(
        val stack: List<Node> = listOf(Node.Home),
        val sort: PlaylistSort = PlaylistSort.Title,
        val albums: List<ProviderAlbum> = emptyList(),
        val artists: List<ProviderArtist> = emptyList(),
        val tracks: List<ProviderTrack> = emptyList(),
        val filter: String = "",
        val busy: Boolean = false,
        val failure: String? = null,
        val indexState: IndexState = IndexState(),
        val hasIndex: Boolean = false,
    )

    sealed interface Event {
        data class SortChanged(val sort: PlaylistSort) : Event
        data class PushArtist(val id: String, val name: String) : Event
        data class PushAlbum(val id: String, val name: String, val artist: String) : Event
        data object Pop : Event
        data object Reindex : Event
        data class FilterChanged(val text: String) : Event
    }

    private val client = account.browseClient()

    private val index: StateFlow<IndexState> = client.index ?: MutableStateFlow(IndexState())

    private val stack = MutableStateFlow<List<Node>>(listOf(Node.Home))
    private val sort = MutableStateFlow(PlaylistSort.Title)
    private val albums = MutableStateFlow<List<ProviderAlbum>>(emptyList())
    private val artists = MutableStateFlow<List<ProviderArtist>>(emptyList())
    private val tracks = MutableStateFlow<List<ProviderTrack>>(emptyList())
    private val filter = MutableStateFlow("")
    private val busy = MutableStateFlow(false)
    private val failure = MutableStateFlow<String?>(null)

    val state: StateFlow<UiState> = combine(
        combine(stack, sort, albums) { s, r, al -> Triple(s, r, al) },
        combine(artists, tracks, busy) { ar, t, b -> Triple(ar, t, b) },
        combine(failure, index) { f, idx -> f to idx },
        filter,
    ) { x, y, z, q ->
        UiState(
            stack = x.first,
            sort = x.second,
            albums = x.third.filter { matchesFilter(q, it.name, it.artist) },
            artists = y.first.filter { matchesFilter(q, it.name) },
            tracks = y.second.filter { matchesFilter(q, it.title, it.artist, it.album) },
            filter = q,
            busy = y.third,
            failure = z.first,
            indexState = z.second,
            hasIndex = client.index != null,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiState(
            indexState = index.value,
            hasIndex = client.index != null,
        ),
    )

    init {
        // Keyed on the scan too: an index that has just finished filling in is a
        // different answer to the same question. collectLatest restarts the fetch
        // the way the screen's keyed effect did.
        viewModelScope.launch {
            combine(stack, sort, index) { s, r, idx -> Triple(s.last(), r, idx.scanning) }
                .distinctUntilChanged()
                .collectLatest { (here, sort) -> fetch(here, sort) }
        }
    }

    fun coverOf(trackId: String): String = client.trackCover(trackId)

    fun onEvent(e: Event) {
        when (e) {
            // A new list is a new sort and filter scope: carrying either over
            // would open the next list already rearranged by nothing relevant.
            is Event.SortChanged -> { sort.value = e.sort; filter.value = "" }
            is Event.PushArtist -> {
                stack.value = stack.value + Node.Artist(e.id, e.name); filter.value = ""
            }
            is Event.PushAlbum -> {
                stack.value = stack.value + Node.Album(e.id, e.name, e.artist); filter.value = ""
            }
            // Popping past home is the caller's back navigation, never ours.
            Event.Pop -> if (stack.value.size > 1) {
                stack.value = stack.value.dropLast(1); filter.value = ""
            }
            Event.Reindex -> {
                if (client.index == null || index.value.scanning) return
                viewModelScope.launch { client.reindex() }
            }
            is Event.FilterChanged -> filter.value = e.text
        }
    }

    private suspend fun fetch(here: Node, sort: PlaylistSort) {
        busy.value = true
        failure.value = null
        albums.value = emptyList(); artists.value = emptyList(); tracks.value = emptyList()
        when (here) {
            Node.Home -> when (sort) {
                PlaylistSort.Artist -> client.artists()
                    .onSuccess { artists.value = it.sortedByName() }
                    .onFailure { failure.value = it.message }
                PlaylistSort.RecentlyAdded -> client.albums("newest")
                    .onSuccess { albums.value = it }
                    .onFailure { failure.value = it.message }
                else -> client.albums("az")
                    .onSuccess { albums.value = it.sortedAlbums(sort) }
                    .onFailure { failure.value = it.message }
            }
            is Node.Artist -> client.artistAlbums(here.id)
                .onSuccess { albums.value = it.sortedAlbums(sort) }
                .onFailure { failure.value = it.message }
            is Node.Album -> client.albumTracks(here.id)
                .onSuccess { tracks.value = it.sortedTracks(sort) }
                .onFailure { failure.value = it.message }
        }
        busy.value = false
    }
}

/** Client-side narrowing of the loaded list, like the folder filter on local songs. */
private fun matchesFilter(text: String, vararg haystacks: String): Boolean {
    if (text.isBlank()) return true
    val q = text.trim().lowercase()
    return haystacks.any { it.lowercase().contains(q) }
}

/** Local-songs ordering applied to provider rows; title stays the tiebreak. */
private fun List<ProviderAlbum>.sortedAlbums(sort: PlaylistSort): List<ProviderAlbum> {
    val title = compareBy<ProviderAlbum> { it.name.lowercase() }
    return when (sort) {
        PlaylistSort.Title, PlaylistSort.Album -> sortedWith(title)
        PlaylistSort.Artist -> sortedWith(compareBy<ProviderAlbum> { it.artist.lowercase() }.then(title))
        PlaylistSort.RecentlyAdded -> this
    }
}

private fun List<ProviderArtist>.sortedByName(): List<ProviderArtist> =
    sortedWith(compareBy<ProviderArtist> { it.name.lowercase() })

private fun List<ProviderTrack>.sortedTracks(sort: PlaylistSort): List<ProviderTrack> {
    val title = compareBy<ProviderTrack> { it.title.lowercase() }
    return when (sort) {
        PlaylistSort.Title -> sortedWith(title)
        PlaylistSort.Artist -> sortedWith(compareBy<ProviderTrack> { it.artist.lowercase() }.then(title))
        PlaylistSort.Album -> sortedWith(compareBy<ProviderTrack> { it.album.lowercase() }.then(title))
        PlaylistSort.RecentlyAdded -> this
    }
}
