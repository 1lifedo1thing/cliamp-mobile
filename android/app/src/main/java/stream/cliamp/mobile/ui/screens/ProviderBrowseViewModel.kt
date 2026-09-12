package stream.cliamp.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.provider.IndexState
import stream.cliamp.mobile.data.provider.ProviderAccount
import stream.cliamp.mobile.data.provider.ProviderAlbum
import stream.cliamp.mobile.data.provider.ProviderArtist
import stream.cliamp.mobile.data.provider.ProviderTrack
import stream.cliamp.mobile.data.provider.browseClient

enum class Root(val label: String, val listType: String) {
    Newest("newest", "newest"),
    Frequent("most played", "frequent"),
    AZ("a-z", "az"),
    Artists("artists", ""),
    Starred("starred", ""),
}

/** Where in the provider's own hierarchy we are. */
sealed interface Node {
    data object Home : Node
    data class Artist(val id: String, val name: String) : Node
    data class Album(val id: String, val name: String, val artist: String) : Node
}

/**
 * Browses one provider's library: albums, artists, starred tracks, drilling
 * into an album's track list. Owns the browse stack, the selected root, the
 * fetched lists and the fetch itself; navigation and playback stay with the
 * caller.
 */
class ProviderBrowseViewModel(
    val account: ProviderAccount,
) : ViewModel() {
    data class UiState(
        val roots: List<Root> = emptyList(),
        val stack: List<Node> = listOf(Node.Home),
        val root: Root = Root.Newest,
        val albums: List<ProviderAlbum> = emptyList(),
        val artists: List<ProviderArtist> = emptyList(),
        val tracks: List<ProviderTrack> = emptyList(),
        val busy: Boolean = false,
        val failure: String? = null,
        val indexState: IndexState = IndexState(),
        val hasIndex: Boolean = false,
    )

    sealed interface Event {
        data class SelectRoot(val root: Root) : Event
        data class PushArtist(val id: String, val name: String) : Event
        data class PushAlbum(val id: String, val name: String, val artist: String) : Event
        data object Pop : Event
        data object Reindex : Event
    }

    private val client = account.browseClient()

    // Roots with no semantics on the server are dropped from the chip row.
    // A filesystem has play counts and stars nowhere; newest is the
    // newest file in a folder, which is when it was copied across.
    private val roots: List<Root> = when (account.providerKey) {
        "jellyfin", "emby", "plex", "abs" -> listOf(Root.Newest, Root.AZ)
        "ssh" -> listOf(Root.Newest, Root.AZ, Root.Artists)
        else -> Root.entries
    }

    private val index: StateFlow<IndexState> = client.index ?: MutableStateFlow(IndexState())

    private val stack = MutableStateFlow<List<Node>>(listOf(Node.Home))
    private val root = MutableStateFlow(roots.first())
    private val albums = MutableStateFlow<List<ProviderAlbum>>(emptyList())
    private val artists = MutableStateFlow<List<ProviderArtist>>(emptyList())
    private val tracks = MutableStateFlow<List<ProviderTrack>>(emptyList())
    private val busy = MutableStateFlow(false)
    private val failure = MutableStateFlow<String?>(null)

    val state: StateFlow<UiState> = combine(
        combine(stack, root, albums) { s, r, al -> Triple(s, r, al) },
        combine(artists, tracks, busy) { ar, t, b -> Triple(ar, t, b) },
        combine(failure, index) { f, idx -> f to idx },
    ) { x, y, z ->
        UiState(
            roots = roots,
            stack = x.first,
            root = x.second,
            albums = x.third,
            artists = y.first,
            tracks = y.second,
            busy = y.third,
            failure = z.first,
            indexState = z.second,
            hasIndex = client.index != null,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiState(
            roots = roots,
            root = roots.first(),
            indexState = index.value,
            hasIndex = client.index != null,
        ),
    )

    init {
        // Keyed on the scan too: an index that has just finished filling in is a
        // different answer to the same question. collectLatest restarts the fetch
        // the way the screen's keyed effect did.
        viewModelScope.launch {
            combine(stack, root, index) { s, r, idx -> Triple(s.last(), r, idx.scanning) }
                .distinctUntilChanged()
                .collectLatest { (here, root) -> fetch(here, root) }
        }
    }

    fun coverOf(trackId: String): String = client.trackCover(trackId)

    fun onEvent(e: Event) {
        when (e) {
            is Event.SelectRoot -> root.value = e.root
            is Event.PushArtist -> stack.value = stack.value + Node.Artist(e.id, e.name)
            is Event.PushAlbum -> stack.value = stack.value + Node.Album(e.id, e.name, e.artist)
            // Popping past home is the caller's back navigation, never ours.
            Event.Pop -> if (stack.value.size > 1) stack.value = stack.value.dropLast(1)
            Event.Reindex -> {
                if (client.index == null || index.value.scanning) return
                viewModelScope.launch { client.reindex() }
            }
        }
    }

    private suspend fun fetch(here: Node, root: Root) {
        busy.value = true
        failure.value = null
        albums.value = emptyList(); artists.value = emptyList(); tracks.value = emptyList()
        when (here) {
            Node.Home -> when (root) {
                Root.Artists -> client.artists()
                    .onSuccess { artists.value = it }
                    .onFailure { failure.value = it.message }
                Root.Starred -> client.starred()
                    .onSuccess { tracks.value = it }
                    .onFailure { failure.value = it.message }
                else -> client.albums(root.listType)
                    .onSuccess { albums.value = it }
                    .onFailure { failure.value = it.message }
            }
            is Node.Artist -> client.artistAlbums(here.id)
                .onSuccess { albums.value = it }
                .onFailure { failure.value = it.message }
            is Node.Album -> client.albumTracks(here.id)
                .onSuccess { tracks.value = it }
                .onFailure { failure.value = it.message }
        }
        busy.value = false
    }
}
