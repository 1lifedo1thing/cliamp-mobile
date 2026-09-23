package stream.kleeamp.mobile.ui.screens

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import stream.kleeamp.mobile.data.LocalArt
import stream.kleeamp.mobile.data.StationArtSource
import stream.kleeamp.mobile.data.LocalLibrary
import stream.kleeamp.mobile.data.PlaylistStore
import stream.kleeamp.mobile.data.PodcastShow
import stream.kleeamp.mobile.data.Repository
import stream.kleeamp.mobile.data.ShowState
import stream.kleeamp.mobile.data.Station
import stream.kleeamp.mobile.data.DirectoryState
import stream.kleeamp.mobile.data.EpisodeProgress
import stream.kleeamp.mobile.data.StationSource
import stream.kleeamp.mobile.data.toStation
import stream.kleeamp.mobile.data.PlaylistSort
import stream.kleeamp.mobile.data.durationLabel
import stream.kleeamp.mobile.data.downloadSizeLabel
import stream.kleeamp.mobile.data.sortedStations
import stream.kleeamp.mobile.data.provider.ProviderAccount
import stream.kleeamp.mobile.data.provider.ProviderCatalog
import stream.kleeamp.mobile.data.provider.displayName
import stream.kleeamp.mobile.data.provider.ProviderSpec
import stream.kleeamp.mobile.data.provider.SftpLibrary
import stream.kleeamp.mobile.chrome.rememberStationThumbnail
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.ChipDropdown
import stream.kleeamp.mobile.chrome.ChipOption
import stream.kleeamp.mobile.chrome.FilterRow
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.KleeampTextField
import stream.kleeamp.mobile.chrome.GlyphPlate
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.HairlineDivider
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.OverflowButton
import stream.kleeamp.mobile.chrome.OverflowItem
import stream.kleeamp.mobile.chrome.OverflowMenu
import stream.kleeamp.mobile.chrome.ScreenHeader
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.chrome.scrollToTop
import stream.kleeamp.mobile.chrome.ArtGlow
import stream.kleeamp.mobile.chrome.MainLayout
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/** The pinned, auto-populated smart playlists on the library tab. */
enum class SmartKind(val label: String) {
    LocalSongs("local songs"),
    Downloads("downloads"),
    Favorites("favorites"),
    RecentlyPlayed("recently played");

    val key: String get() = "smart:$name"
}

/** The favourites playlist's type sub-tabs. */
enum class FavScope(val label: String) {
    All("all"), Local("local"), Stations("stations"), Pods("podcasts")
}

/** A derived smart playlist: a label plus its current member stations. */
class SmartPlaylist(val kind: SmartKind, val stations: List<Station>) {
    val label: String get() = kind.label
    val key: String get() = kind.key
}

/** One on-device folder and the songs MediaStore found inside it. */
data class SongFolder(val path: String, val name: String, val songs: List<Station>)

/**
 * Groups the library by parent directory, folders by name. Derived from the
 * songs flow, so it tracks rescans and deletions with nothing stored.
 */
fun foldersOf(songs: List<Station>): List<SongFolder> {
    val byDir = LinkedHashMap<String, MutableList<Station>>()
    songs.forEach { s ->
        if (!s.url.startsWith("file://")) return@forEach
        val dir = java.io.File(android.net.Uri.decode(s.url.removePrefix("file://"))).parent
            ?: return@forEach
        byDir.getOrPut(dir) { mutableListOf() } += s
    }
    return byDir
        .map { (dir, list) ->
            SongFolder(dir, java.io.File(dir).name.ifBlank { dir }, list.sortedBy { it.name.lowercase() })
        }
        .sortedBy { it.name.lowercase() }
}

/**
 * Narrows stations by name, artist or album for the playlist filter boxes.
 * Blank query passes the list through untouched.
 */
fun List<Station>.matching(query: String): List<Station> {
    val q = query.trim().lowercase()
    if (q.isBlank()) return this
    return filter {
        it.name.lowercase().contains(q) ||
            it.artist.lowercase().contains(q) ||
            it.album.lowercase().contains(q)
    }
}

/**
 * The LIB tab's local half. Reads the phone's audio library via [LocalLibrary]
 * and lets the user play any song (through the normal Station pipeline, so
 * queue/prev/next/artwork all just work) and build playlists with covers.
 *
 * This renders the list only. Detail pages (providers, smart playlist,
 * playlist, song info) are separate navigation destinations
 * ([LibraryProvidersPane], [LibrarySmartPlaylistPane], [LibraryPlaylistPane],
 * [LibrarySongInfoPane]) so the back gesture animates them with the native
 * slide+scale transition while the mini player stays visible beneath.
 */
@Composable
fun LocalScreen(
    vm: LocalViewModel,
    onOpenProviderSongs: () -> Unit = {},
    onOpenSmart: (String) -> Unit = {},
    onOpenPlaylist: (String) -> Unit = {},
    onPickSongs: (String) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    favScope: FavScope = FavScope.All,
    /** False while the pager sits on another tab: the naming field hides. */
    visible: Boolean = true,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var creatingName by rememberSaveable { mutableStateOf(false) }
    var renamingSlug by rememberSaveable { mutableStateOf<String?>(null) }
    var nameText by rememberSaveable { mutableStateOf("") }

    val ui by vm.state.collectAsState()
    val songs = ui.songs
    val libError = ui.error
    val allPlaylists = ui.allPlaylists
    val pinnedPlaylists = ui.pinnedPlaylists
    val unpinnedPlaylists = ui.unpinnedPlaylists
    val favorites = ui.favorites
    val recent = ui.recent
    val localSort = ui.localSort
    val fetched = ui.fetched

    var haveAudio by remember { mutableStateOf(LocalLibrary.hasAudioPermission(context)) }

    // The media permission is requested once at app launch. Recheck it whenever
    // this screen resumes, so granting it in system Settings (without another
    // in-app dialog) is picked up here.
    // The naming field never survives a page change: leaving for another tab
    // or pushing anything above hides it (and drops its text), so coming back
    // always lands on the plain list.
    LaunchedEffect(visible) {
        if (!visible) {
            creatingName = false
            renamingSlug = null
        }
    }
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose {
            creatingName = false
            renamingSlug = null
        }
    }
    LifecycleResumeEffect(Unit) {
        haveAudio = LocalLibrary.hasAudioPermission(context)
        onPauseOrDispose { }
    }

    LaunchedEffect(haveAudio) {
        if (haveAudio) vm.onEvent(LocalViewModel.Event.Refresh)
    }

    val filtered = songs
    // Playing starts minimized; the full player only opens when the mini-player
    // bar at the bottom is tapped.

    // The favourites sub-tab is chosen while browsing the list; the favourite's
    // pinned tile collage follows it, so the covers preview the filtered scope.
    // Hoisted to the navigation owner so the list and the smart detail pane
    // stay on the same scope.
    // Pinned smart playlists — auto-populated from global state, non-removable.
    // Order is recency first: recently played, downloads, favorites, local songs.
    val smartPlaylists = remember(filtered, favorites, recent, localSort, favScope, fetched) {
        val local = sortedStations(filtered, localSort)
        val favs = if (favScope == FavScope.All) favorites
        else favorites.filter { s ->
            when (favScope) {
                FavScope.Local -> s.source == StationSource.Local
                FavScope.Stations -> s.source != StationSource.Local && s.source != StationSource.Podcast
                FavScope.Pods -> s.source == StationSource.Podcast
                FavScope.All -> true
            }
        }
        listOf(
            SmartPlaylist(SmartKind.RecentlyPlayed, recent),
            SmartPlaylist(SmartKind.Downloads, sortedStations(fetched.values.map { it.station }, localSort)),
            SmartPlaylist(SmartKind.Favorites, favs),
            SmartPlaylist(SmartKind.LocalSongs, local),
        )
    }
    // ---- base page (always composed) ----
    Box(
        Modifier
            .fillMaxSize()
            .background(p.ground),
    ) {
    val listState = rememberLazyListState()
    MainLayout(
            title = "Library",
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(listState) },
        ) {

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !haveAudio -> PermissionNote()
                libError != null && songs.isEmpty() -> CenterNote(libError!!, p.destructiveInk)
                else -> PlaylistList(
                    listState = listState,
                    smart = smartPlaylists,
                    providerAccounts = ui.providerAccounts,
                    onOpenProviderSongs = onOpenProviderSongs,
                    pinnedPlaylists = pinnedPlaylists,
                    playlists = unpinnedPlaylists,
                    songs = songs,
                    creating = creatingName,
                    renamingSlug = renamingSlug,
                    editText = nameText,
                    onEditTextChange = { nameText = it },
                    onCreate = { name -> vm.onEvent(LocalViewModel.Event.Create(name)); creatingName = false },
                    onBeginCreate = { creatingName = true; nameText = "" },
                    onCancel = { creatingName = false; renamingSlug = null },
                    onRename = { slug, name ->
                        vm.onEvent(LocalViewModel.Event.Rename(slug, name))
                        renamingSlug = null
                    },
                    onBeginRename = { slug ->
                        renamingSlug = slug
                        nameText = allPlaylists.firstOrNull { it.station.slug == slug }?.station?.name.orEmpty()
                    },
                    onDelete = { slug ->
                        vm.onEvent(LocalViewModel.Event.Delete(slug))
                        if (renamingSlug == slug) renamingSlug = null
                    },
                    onAddSongs = { slug -> onPickSongs(slug) },
                    onPin = { slug, pinned -> vm.onEvent(LocalViewModel.Event.SetPinned(slug, pinned)) },
                    onOpen = { onOpenPlaylist(it.station.slug) },
                    onOpenSmart = { onOpenSmart(it.kind.name) },
                )
            }
        }
        }
    }
}

/** Providers as a navigation pane: connected accounts, then every addable type. */
@Composable
fun LibraryProvidersPane(
    vm: ProvidersPaneViewModel,
    onBack: () -> Unit,
    onOpenProvider: (ProviderAccount) -> Unit,
    onEditProvider: (ProviderAccount) -> Unit,
    onAddProvider: (ProviderSpec) -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val p = LocalPalette.current
    val ui by vm.state.collectAsState()
    Box(Modifier.fillMaxSize().background(p.ground)) {
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        MainLayout(
            title = "providers",
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(listState) },
            onBack = onBack,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ProvidersView(
                    listState = listState,
                    providers = ui.providers,
                    onOpenProvider = onOpenProvider,
                    onEditProvider = onEditProvider,
                    onAddProvider = onAddProvider,
                    onRemoveProvider = { vm.onEvent(ProvidersPaneViewModel.Event.Remove(it)) },
                )
            }
        }
    }
}

/**
 * One pinned smart playlist as a navigation pane. Derives its members from
 * the same sources as the library list so the tile collage and the detail
 * agree exactly.
 */
@Composable
fun LibrarySmartPlaylistPane(
    vm: SmartPlaylistViewModel,
    kindName: String,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onAddToQueue: (Station) -> Unit = {},
    favScope: FavScope = FavScope.All,
    onFavScopeChange: (FavScope) -> Unit = {},
    onOpenSongInfo: (Station) -> Unit = {},
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** Saved positions by station URL, for the resume readout on local rows. */
    progress: Map<String, EpisodeProgress> = emptyMap(),
    onAddToPlaylist: (Station) -> Unit = {},
) {
    val p = LocalPalette.current
    val ui by vm.state.collectAsState()
    val kind = remember(kindName) {
        SmartKind.entries.firstOrNull { it.name == kindName }
    }
    val songs = ui.songs
    val loading = ui.loading
    val localSort = ui.localSort
    val detailSort = ui.detailSort
    val fetched = ui.fetched
    val favorites = ui.favorites
    // Recently-played re-sorts itself on every tap (the tap pushes history),
    // so a live list would jump under the finger and next/prev would chase a
    // moving order. Freeze the view on entry like a normal playlist - the
    // queue then matches exactly what is on screen, and the fresh order lands
    // on return. Other lists stay live; only history reorders on play.
    // Frozen inside SmartPlaylistViewModel to keep the queue stable.
    val viewRecent = ui.viewRecent
    val showResume = ui.resumeLocal
    var pendingDelete by remember { mutableStateOf<Station?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        pendingDelete?.let { s ->
            if (res.resultCode == Activity.RESULT_OK) {
                vm.onEvent(SmartPlaylistViewModel.Event.DeleteLocal(s))
            }
            pendingDelete = null
        }
    }
    val removeLocalSong: (Station) -> Unit = { s ->
        val start = vm.deleteRequest(s)
        if (start != null) {
            pendingDelete = s
            deleteLauncher.launch(IntentSenderRequest.Builder(start).build())
        } else {
            vm.onEvent(SmartPlaylistViewModel.Event.DeleteLocal(s))
        }
    }
    val smartPlaylists = remember(songs, favorites, viewRecent, localSort, favScope, fetched) {
        val local = sortedStations(songs, localSort)
        val favs = if (favScope == FavScope.All) favorites
        else favorites.filter { s ->
            when (favScope) {
                FavScope.Local -> s.source == StationSource.Local
                FavScope.Stations -> s.source != StationSource.Local && s.source != StationSource.Podcast
                FavScope.Pods -> s.source == StationSource.Podcast
                FavScope.All -> true
            }
        }
        listOf(
            SmartPlaylist(SmartKind.RecentlyPlayed, viewRecent),
            SmartPlaylist(SmartKind.Downloads, sortedStations(fetched.values.map { it.station }, localSort)),
            SmartPlaylist(SmartKind.Favorites, favs),
            SmartPlaylist(SmartKind.LocalSongs, local),
        )
    }
    val pl = smartPlaylists.firstOrNull { it.kind == kind }
    // Local songs filter by folder through a picker dropdown that leads the
    // sort row - one scrollable header row, no mode switching, no drill state.
    val folders = remember(pl?.stations) {
        if (pl?.kind == SmartKind.LocalSongs) foldersOf(pl.stations) else emptyList()
    }
    var folder by rememberSaveable(pl?.key ?: "playlist") { mutableStateOf<String?>(null) }
    // A deleted folder must not leave the filter pointing at nothing.
    LaunchedEffect(folders) {
        if (folder != null && folders.none { it.path == folder }) folder = null
    }
    val members = if (pl?.kind == SmartKind.LocalSongs && folder != null) {
        folders.firstOrNull { it.path == folder }?.songs.orEmpty()
    } else pl?.stations.orEmpty()
    // The header carries no chip rows; pickers ride the scrolling rows
    // below, on top of the filter.
    Box(Modifier.fillMaxSize().background(p.ground)) {
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        // Favorites grows through the same song picker playlists use; back
        // closes it, writes already landed per tap.
        var adding by rememberSaveable(pl?.key ?: "playlist") { mutableStateOf(false) }
        BackHandler(enabled = adding) { adding = false }
        MainLayout(
            title = pl?.label ?: "playlist",
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(listState) },
            onBack = onBack,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (pl == null) {
                    CenterNote("no such playlist", p.inkTertiary)
                } else if (adding && pl.kind == SmartKind.Favorites) {
                    AddSongsPicker(
                        selected = favorites.map { it.id }.toSet(),
                        playlistName = pl.label,
                        localSongs = songs,
                        radioStations = ui.radioStations,
                        subscribedShows = ui.subscribedShows,
                        showState = ui.showState,
                        onOpenShow = { vm.onEvent(SmartPlaylistViewModel.Event.OpenShow(it)) },
                        onToggle = { vm.onEvent(SmartPlaylistViewModel.Event.ToggleFavorite(it)) },
                    )
                } else {
                    SmartPlaylistDetail(
                        listState = listState,
                        pl = pl,
                        members = members,
                        current = current,
                        playing = playing,
                        onPlay = onPlay,
                        onToggleFavorite = { vm.onEvent(SmartPlaylistViewModel.Event.ToggleFavorite(it)) },
                        favorites = favorites.map { it.url }.toSet(),
                        onAddToQueue = onAddToQueue,
                        loading = loading,
                        favScope = favScope,
                        onFavScopeChange = onFavScopeChange,
                        onSortChange = { vm.onEvent(SmartPlaylistViewModel.Event.SetSort(it)) },
                        folders = folders,
                        folder = folder,
                        onFolder = { folder = it },
                        onInfo = onOpenSongInfo,
                        // Removing from downloads deletes the fetched file and
                        // untracks the URL; anywhere else it drops the song.
                        onRemove = { s ->
                            if (pl.kind == SmartKind.Downloads) {
                                vm.onEvent(SmartPlaylistViewModel.Event.RemoveDownload(s))
                            } else removeLocalSong(s)
                        },
                        progress = progress,
                        showResume = showResume,
                        sort = detailSort,
                        fetchedBytes = fetched.mapValues { it.value.bytes },
                        onAddToPlaylist = onAddToPlaylist,
                        onBeginAdd = if (pl.kind == SmartKind.Favorites) {
                            { adding = true }
                        } else null,
                    )
                }
            }
        }
    }
}

/**
 * The "providers" playlist: one chip per connected account, each showing
 * that account's songs in one flat list, sorted and filtered exactly like
 * the local-songs chips (title / artist / album / recently added, plus a
 * text filter). The + in the section header opens the providers pane to
 * connect another account - the add-playlist button's counterpart for
 * accounts. Rows play exactly like smart-playlist rows, favourites
 * included; provider tracks carry their own cover URLs and fall back to
 * the same plate local songs wear.
 *
 * The artist chip is a drill, not just a sort: it lists distinct artist
 * names first, picking one lists that artist's albums, and picking an
 * album lands on its songs - the same three-level shape as the browse
 * screen's node stack, but over the flat list already loaded for this
 * account rather than fresh server calls. The back chevron pops one
 * level of the drill before it ever reaches [onBack].
 */
private data class ProviderAlbumRow(val name: String, val count: Int, val year: Int)

@Composable
fun ProviderSongsPane(
    vm: ProviderSongsViewModel,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onAddToQueue: (Station) -> Unit = {},
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** Non-blank locks the pane to one account with no picker row. */
    accountId: String = "",
) {
    val p = LocalPalette.current
    val ui by vm.state.collectAsState()
    // A locked pane shows that account only, with no picker: the row it came
    // from already named it.
    val accounts = remember(ui.accounts, accountId) {
        if (accountId.isBlank()) ui.accounts
        else ui.accounts.filter { it.id == accountId }
    }
    var selected by rememberSaveable(accountId) { mutableStateOf(accounts.firstOrNull()?.id) }
    // A removed account must not leave the filter pointing at nothing.
    LaunchedEffect(accounts) {
        if (accounts.none { it.id == selected }) selected = accounts.firstOrNull()?.id
    }
    var sort by rememberSaveable { mutableStateOf(PlaylistSort.Title) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedArtist by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    val pool = ui.songsByAccount[selected].orEmpty()
    val browsingArtists = sort == PlaylistSort.Artist && selectedArtist == null
    val browsingAlbums = sort == PlaylistSort.Artist && selectedArtist != null && selectedAlbum == null
    val artistRows = remember(pool, sort, query) {
        if (sort != PlaylistSort.Artist) emptyList() else {
            val q = query.trim().lowercase()
            pool.groupBy { it.artist.ifBlank { "unknown artist" } }
                .filterKeys { q.isBlank() || it.lowercase().contains(q) }
                .toSortedMap(compareBy { it.lowercase() })
                .map { (name, songs) -> name to songs.size }
        }
    }
    val albumYears = ui.albumYearsByAccount[selected].orEmpty()
    val albumRows = remember(pool, sort, selectedArtist, query, albumYears) {
        val artist = selectedArtist
        if (!browsingAlbums || artist == null) emptyList() else {
            val q = query.trim().lowercase()
            pool.filter { it.artist.ifBlank { "unknown artist" } == artist }
                .groupBy { it.album.ifBlank { "unknown album" } }
                .filterKeys { q.isBlank() || it.lowercase().contains(q) }
                .map { (name, songs) ->
                    ProviderAlbumRow(name, songs.size, albumYears[albumYearKey(artist, name)] ?: 0)
                }
                // Chronological, oldest first; albums with no known year sort
                // last by name rather than pretending to be from year zero.
                .sortedWith(
                    compareBy<ProviderAlbumRow> { it.year <= 0 }
                        .thenBy { it.year }
                        .thenBy { it.name.lowercase() },
                )
        }
    }
    val visible = remember(pool, sort, query, selectedArtist, selectedAlbum) {
        val songsOfAlbum = sort == PlaylistSort.Artist && selectedArtist != null && selectedAlbum != null
        val scoped = when {
            songsOfAlbum ->
                pool.filter {
                    it.artist.ifBlank { "unknown artist" } == selectedArtist &&
                        it.album.ifBlank { "unknown album" } == selectedAlbum
                }
            sort != PlaylistSort.Artist -> pool
            else -> emptyList()
        }
        val q = query.trim().lowercase()
        val matching = if (q.isBlank()) scoped else scoped.filter {
            it.name.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }
        // An album's tracks are already in the server's track-number order;
        // re-sorting them alphabetically would scramble the album's own
        // running order, so only the other views get a client-side sort.
        if (songsOfAlbum) matching else sortedStations(matching, if (sort == PlaylistSort.Artist) PlaylistSort.Title else sort)
    }
    val favorites = ui.favorites.map { it.url }.toSet()
    val failedLabels = ui.failures.keys.mapNotNull { id ->
        accounts.firstOrNull { it.id == id }?.displayName()?.ifBlank { null }
    }
    fun pop() {
        when {
            selectedAlbum != null -> { selectedAlbum = null; query = "" }
            selectedArtist != null -> { selectedArtist = null; query = "" }
            else -> onBack()
        }
    }
    BackHandler(enabled = selectedArtist != null) { pop() }
    Box(Modifier.fillMaxSize().background(p.ground)) {
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        MainLayout(
            title = if (accountId.isBlank()) {
                selectedAlbum ?: selectedArtist ?: "providers"
            } else {
                accounts.firstOrNull()?.displayName()?.ifBlank { "provider" } ?: "provider"
            },
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(listState) },
            onBack = { pop() },
            chips = if (accounts.size > 1) {
                @Composable {
                    accounts.forEach { a ->
                        Chip(
                            a.displayName().ifBlank { "provider" },
                            selected == a.id,
                            onClick = {
                                selected = a.id; query = ""; selectedArtist = null; selectedAlbum = null
                            },
                        )
                    }
                }
            } else null,
        ) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                // Picker rows scroll with the list; only the header is
                // fixed. Sort first, rescan trailing for SSH accounts.
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(start = Gutter, end = Gutter, top = 10.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        PlaylistSort.entries.forEach { s ->
                            Chip(
                                s.label, sort == s,
                                onClick = { sort = s; query = ""; selectedArtist = null; selectedAlbum = null },
                            )
                        }
                        // The browse page is gone; its rescan rides the sort row for
                        // SSH accounts, trailing like it always did.
                        val selectedAccount = accounts.firstOrNull { it.id == selected }
                        if (selectedAccount?.providerKey == "ssh") {
                            val indexState by SftpLibrary.status(selectedAccount.id).collectAsState()
                            Chip(
                                if (indexState.scanning) "scanning" else "rescan",
                                selected = false,
                                onClick = { scope.launch { SftpLibrary.rescan(selectedAccount) } },
                            )
                        }
                    }
                }
                item { FilterRow(value = query, onValue = { query = it }) }
                item {
                    // Adding happens on the providers page; this page only
                    // shows what is already connected.
                    SectionLabel(
                        when {
                            browsingArtists -> "artists — ${artistRows.size}"
                            browsingAlbums -> "albums — ${albumRows.size}"
                            else -> "songs — ${visible.size}"
                        },
                    )
                }
                if (browsingArtists) {
                    if (artistRows.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                                Mono(
                                    if (ui.loading) "loading provider songs…" else "nothing here",
                                    KleeampType.rowSecondary, p.inkFaint,
                                )
                            }
                        }
                    } else {
                        items(artistRows, key = { it.first }, contentType = { "provider-artist" }) { (name, count) ->
                            ListRow(
                                onClick = { selectedArtist = name; selectedAlbum = null; query = "" },
                                verticalPadding = 11.dp,
                                trailing = { Mono("$count", KleeampType.meta, p.inkFaint) },
                            ) {
                                Mono(name, KleeampType.rowPrimary, p.ink, maxLines = 1)
                            }
                        }
                    }
                } else if (browsingAlbums) {
                    if (albumRows.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                                Mono("nothing here", KleeampType.rowSecondary, p.inkFaint)
                            }
                        }
                    } else {
                        items(albumRows, key = { it.name }, contentType = { "provider-album" }) { row ->
                            ListRow(
                                onClick = { selectedAlbum = row.name; query = "" },
                                verticalPadding = 11.dp,
                                trailing = { Mono("${row.count}", KleeampType.meta, p.inkFaint) },
                            ) {
                                Mono(row.name, KleeampType.rowPrimary, p.ink, maxLines = 1)
                                if (row.year > 0) {
                                    Mono("${row.year}", KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
                                }
                            }
                        }
                    }
                } else if (visible.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            Mono(
                                when {
                                    accounts.isEmpty() -> "no providers yet — add one with +"
                                    ui.loading -> "loading provider songs…"
                                    else -> "nothing here"
                                },
                                KleeampType.rowSecondary, p.inkFaint,
                            )
                        }
                    }
                } else {
                    items(visible, key = { it.id }, contentType = { "provider-song" }) { s ->
                        ListRow(
                            rail = current?.url == s.url,
                            onClick = { onPlay(s, visible) },
                            verticalPadding = 9.dp,
                            leading = {
                                SongCover(s = s, current = current, playing = playing)
                            },
                            trailing = {
                                Icon(
                                    if (s.url in favorites) KleeampIcons.StarFilled else KleeampIcons.Star,
                                    "favourite",
                                    Modifier.size(15.dp).microPress {
                                        vm.onEvent(ProviderSongsViewModel.Event.ToggleFavorite(s))
                                    },
                                    tint = if (s.url in favorites) p.accent else p.inkFaint,
                                )
                            },
                            onQueue = { onAddToQueue(s) },
                        ) {
                            Mono(s.name, KleeampType.rowPrimary, if (current?.url == s.url) p.accent else p.ink, maxLines = 1)
                            Mono(
                                s.artistAlbum.ifBlank { s.meta.ifBlank { "provider" } },
                                KleeampType.rowSecondary, p.inkTertiary, maxLines = 1,
                            )
                        }
                    }
                }
                if (failedLabels.isNotEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = Gutter, vertical = 8.dp)
                                .microPress { vm.onEvent(ProviderSongsViewModel.Event.Refresh) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Mono(
                                "couldn't reach ${failedLabels.joinToString(", ")} — tap to retry",
                                KleeampType.rowSecondary, p.destructiveInk,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

/** One user playlist as a navigation pane, with add-songs and cover editing. */
@Composable
fun LibraryPlaylistPane(
    vm: PlaylistDetailViewModel,
    slug: String,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onAddToPlaylist: (Station) -> Unit = {},
    onAddToQueue: (Station) -> Unit = {},
    /** True when opened from a playlist row's add menu: lands in the song picker. */
    startAdding: Boolean = false,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var adding by rememberSaveable(slug) { mutableStateOf(startAdding) }
    var query by rememberSaveable(slug) { mutableStateOf("") }
    val ui by vm.state.collectAsState()
    // Back closes the picker first; writes already landed per tap, so there
    // is nothing to save — a second back leaves the page.
    BackHandler(enabled = adding) { adding = false }
    val songs = ui.localSongs
    val radioStations = ui.radioStations
    val subscriptions = ui.subscribedShows
    val pl = ui.playlist
    val coverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val cover = uri?.toString().orEmpty()
        if (cover.isNotBlank()) vm.onEvent(PlaylistDetailViewModel.Event.SetCover(cover))
    }
    Box(Modifier.fillMaxSize().background(p.ground)) {
        val listState = rememberLazyListState()
        MainLayout(
            title = pl?.station?.name ?: "playlist",
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(listState) },
            onBack = onBack,
            chips = if (pl != null) {
                @Composable {
                    Chip("set cover", selected = false, onClick = { coverLauncher.launch("image/*") })
                }
            } else null,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (pl == null) {
                    CenterNote("playlist gone", p.inkTertiary)
                } else {
                    PlaylistDetailShown(
                        listState = listState,
                        playlist = pl,
                        songIds = pl.songIds,
                        members = ui.members,
                        visible = remember(ui.visible, query) { ui.visible.matching(query) },
                        query = query,
                        onQuery = { query = it },
                        sort = ui.sort,
                        onSortChange = { vm.onEvent(PlaylistDetailViewModel.Event.SetSort(it)) },
                        localSongs = songs,
                        radioStations = radioStations,
                        subscribedShows = subscriptions,
                        showState = ui.showState,
                        onOpenShow = { vm.onEvent(PlaylistDetailViewModel.Event.OpenShow(it)) },
                        current = current,
                        playing = playing,
                        onPlay = onPlay,
                        onToggle = { vm.onEvent(PlaylistDetailViewModel.Event.ToggleMember(it)) },
                        onRemoveMember = { vm.onEvent(PlaylistDetailViewModel.Event.RemoveMember(it)) },
                        onAddToQueue = onAddToQueue,
                        adding = adding,
                        onAddToPlaylist = onAddToPlaylist,
                        onBeginAdd = { adding = true },
                        favorites = ui.favorites.map { it.url }.toSet(),
                        onToggleFavorite = { vm.onEvent(PlaylistDetailViewModel.Event.ToggleFavorite(it)) },
                    )
                }
            }
        }
    }
}

/** One song's full detail as a navigation pane. */
@Composable
fun LibrarySongInfoPane(
    vm: SongInfoViewModel,
    stationUrl: String,
    repository: Repository,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val ui by vm.state.collectAsState()
    val songs = ui.songs
    val favorites = ui.favorites
    val recent = ui.recent
    val cliamp by repository.cliamp.collectAsState(initial = emptyList())
    val directory by repository.directory.collectAsState(initial = DirectoryState())
    val station = remember(stationUrl, songs, favorites, recent, cliamp, directory) {
        (songs + favorites + recent + cliamp + directory.stations)
            .distinctBy { it.url }
            .firstOrNull { it.url == stationUrl }
    }
    var pendingDelete by remember { mutableStateOf<Station?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        pendingDelete?.let { s ->
            if (res.resultCode == Activity.RESULT_OK) {
                vm.onEvent(SongInfoViewModel.Event.DeleteLocal(s))
                onBack()
            }
            pendingDelete = null
        }
    }
    val removeLocalSong: (Station) -> Unit = { s ->
        val start = vm.deleteRequest(s)
        if (start != null) {
            pendingDelete = s
            deleteLauncher.launch(IntentSenderRequest.Builder(start).build())
        } else {
            vm.onEvent(SongInfoViewModel.Event.DeleteLocal(s))
            onBack()
        }
    }
    Box(Modifier.fillMaxSize().background(p.ground)) {
        if (station == null) {
            CenterNote("song gone", p.inkTertiary)
        } else {
            SongInfoView(
                s = station,
                systemBack = false,
                onDismiss = onBack,
                onToggleFavorite = { vm.onEvent(SongInfoViewModel.Event.ToggleFavorite(it)) },
                favorite = ui.favorite,
                onRemove = { removeLocalSong(station) },
                plays = ui.plays,
                lastPlayedAt = ui.lastPlayedAt,
            )
        }
    }
}

@Composable
private fun CenterNote(text: String, color: androidx.compose.ui.graphics.Color) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Mono(text, KleeampType.rowSecondary, color)
    }
}

@Composable
private fun PermissionNote() {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(Gutter)) {
        Spacer(Modifier.height(12.dp))
        Mono("this app needs to read your audio files to show them here.", KleeampType.rowPrimary, p.ink)
        Spacer(Modifier.height(4.dp))
        Mono("grant media access in the app's settings to see your local songs.", KleeampType.rowSecondary, p.inkTertiary)
        Spacer(Modifier.height(12.dp))
        HairlineDivider()
    }
}

@Composable
private fun PlaylistList(
    listState: LazyListState,
    smart: List<SmartPlaylist>,
    providerAccounts: List<ProviderAccount> = emptyList(),
    onOpenProviderSongs: () -> Unit = {},
    pinnedPlaylists: List<PlaylistStore.Playlist>,
    playlists: List<PlaylistStore.Playlist>,
    songs: List<Station>,
    creating: Boolean,
    renamingSlug: String?,
    editText: String,
    onEditTextChange: (String) -> Unit,
    onCreate: (String) -> Unit,
    onBeginCreate: () -> Unit,
    onCancel: () -> Unit,
    onRename: (String, String) -> Unit,
    onBeginRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddSongs: (String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onOpen: (PlaylistStore.Playlist) -> Unit,
    onOpenSmart: (SmartPlaylist) -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
        ) {
            // The providers row below is pinned too, so it counts: 4 smart +
            // providers + whatever the user pinned themselves.
            val pinnedCount = smart.size + 1 + pinnedPlaylists.size
            item {
                SectionLabel("pinned — $pinnedCount") {
                    if (!creating) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(KleeampShape.small))
                                .background(if (p.dark) p.keyFace else p.ground)
                                .border(1.dp, p.keyBorder, RoundedCornerShape(KleeampShape.small))
                                .microPress(onClick = onBeginCreate),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(KleeampIcons.Plus, "new playlist", Modifier.size(16.dp), tint = p.accent)
                        }
                    }
                }
            }
            if (creating) {
                item {
                    InlineNameField(
                        text = editText,
                        onTextChange = onEditTextChange,
                        placeholder = "name this playlist",
                        onDone = onCreate,
                        onCancel = onCancel,
                    )
                }
            }
            // Providers sits second: recently played keeps the top spot for
            // daily muscle memory, and the servers list follows it.
            smart.firstOrNull()?.let { first ->
                item(key = first.key) {
                    SmartPlaylistRow(sp = first, onOpen = { onOpenSmart(first) })
                }
            }
            item {
                ProvidersRow(count = providerAccounts.size, onOpen = onOpenProviderSongs)
            }
            items(smart.drop(1), key = { it.key }) { sp ->
                SmartPlaylistRow(sp = sp, onOpen = { onOpenSmart(sp) })
            }

            items(
                pinnedPlaylists,
                key = { it.station.slug },
            ) { pl ->
                if (pl.station.slug == renamingSlug) {
                    InlineNameField(
                        text = editText,
                        onTextChange = onEditTextChange,
                        placeholder = "rename playlist",
                        onDone = { onRename(pl.station.slug, it) },
                        onCancel = onCancel,
                    )
                } else {
                    PlaylistRow(
                        pl = pl,
                        songs = songs,
                        pinned = true,
                        onOpen = onOpen,
                        onEdit = { onBeginRename(pl.station.slug) },
                        onDelete = { onDelete(pl.station.slug) },
                        onAddSongs = { onAddSongs(pl.station.slug) },
                        onPin = { onPin(pl.station.slug, false) },
                    )
                }
            }
            if (playlists.isNotEmpty()) {
                item {
                    SectionLabel("playlists — ${playlists.size}")
                }
            }
            items(
                playlists,
                key = { it.station.slug },
            ) { pl ->
                if (pl.station.slug == renamingSlug) {
                    InlineNameField(
                        text = editText,
                        onTextChange = onEditTextChange,
                        placeholder = "rename playlist",
                        onDone = { onRename(pl.station.slug, it) },
                        onCancel = onCancel,
                    )
                } else {
                    PlaylistRow(
                        pl = pl,
                        songs = songs,
                        pinned = false,
                        onOpen = onOpen,
                        onEdit = { onBeginRename(pl.station.slug) },
                        onDelete = { onDelete(pl.station.slug) },
                        onAddSongs = { onAddSongs(pl.station.slug) },
                        onPin = { onPin(pl.station.slug, true) },
                    )
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Dedicated providers pane: connected accounts, then every addable type. */
@Composable
private fun ProvidersView(
    listState: LazyListState,
    providers: List<ProviderAccount>,
    onOpenProvider: (ProviderAccount) -> Unit,
    onEditProvider: (ProviderAccount) -> Unit,
    onAddProvider: (ProviderSpec) -> Unit,
    onRemoveProvider: (ProviderAccount) -> Unit,
) {
    val p = LocalPalette.current
    val connectedKeys = providers.map { it.providerKey }.toSet()
    // Every provider allows several accounts - two servers of one kind are
    // as ordinary as two SSH hosts - so all specs stay addable.
    val available = ProviderCatalog.all.filter { it.multiple || it.key !in connectedKeys }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            SectionLabel("connected — ${providers.size}") { }
        }
        if (providers.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
                    Mono("nothing connected yet", KleeampType.rowSecondary, p.inkFaint)
                }
            }
        } else {
            items(providers, key = { "prov:${it.id}" }) { acc ->
                ListRow(
                    onClick = { onOpenProvider(acc) },
                    verticalPadding = 11.dp,
                    leading = {
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(KleeampShape.tiny))
                                .border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.tiny)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(KleeampIcons.Server, null, Modifier.size(14.dp), tint = p.amber)
                        }
                    },
                    // Adding an account was always possible and removing one
                    // never was, which mattered little when each provider could
                    // only be connected once and matters a lot now that SSH
                    // hosts can be added without limit.
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OverflowMenu(
                                trigger = { open -> OverflowButton(open, size = 16) },
                                items = listOf(
                                    OverflowItem(
                                        "edit account",
                                        color = p.ink,
                                        action = { onEditProvider(acc) },
                                    ),
                                    OverflowItem(
                                        "remove account",
                                        color = p.destructiveInk,
                                        action = { onRemoveProvider(acc) },
                                    ),
                                ),
                            )
                        }
                    },
                ) {
                    Mono(acc.displayName().ifBlank { "provider" }, KleeampType.rowPrimaryMedium, p.ink, maxLines = 1)
                    Mono(
                        ProviderCatalog.byKey(acc.providerKey)?.summary?.invoke(acc.values)
                            ?: acc.url,
                        KleeampType.rowSecondary, p.inkTertiary, maxLines = 1,
                    )
                }
            }
        }
        item {
            SectionLabel("available — ${available.size}") { }
        }
        if (available.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
                    Mono("every provider is connected", KleeampType.rowSecondary, p.inkFaint)
                }
            }
        } else {
            items(available, key = { "add:${it.key}" }) { spec ->
                ListRow(
                    onClick = { onAddProvider(spec) },
                    verticalPadding = 11.dp,
                    leading = {
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(KleeampShape.tiny))
                                .border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.tiny)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(KleeampIcons.Server, null, Modifier.size(14.dp), tint = p.inkTertiary)
                        }
                    },
                    trailing = {
                        Icon(
                            KleeampIcons.Plus, "add",
                            Modifier.size(11.dp).clip(RoundedCornerShape(KleeampShape.tiny))
                                .background(p.accent.copy(alpha = 0.14f))
                                .padding(6.dp),
                            tint = p.accent,
                        )
                    },
                ) {
                    Mono(spec.name, KleeampType.rowPrimaryMedium, p.ink, maxLines = 1)
                    Mono(spec.intro.firstOrNull().orEmpty(), KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun PlaylistRow(
    pl: PlaylistStore.Playlist,
    songs: List<Station>,
    pinned: Boolean,
    onOpen: (PlaylistStore.Playlist) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddSongs: () -> Unit,
    onPin: () -> Unit,
) {
    val p = LocalPalette.current
    ListRow(
        onClick = { onOpen(pl) },
        verticalPadding = 9.dp,
        leading = {
            PlaylistGlyph(KleeampIcons.ListShort, pl.station.name)
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Mono("${pl.songIds.size} songs", KleeampType.meta, p.inkFaint)
                PlaylistMenu(pinned = pinned, onAddSongs = onAddSongs, onEdit = onEdit, onDelete = onDelete, onPin = onPin)
            }
        },
    ) {
        // Title plus the trailing count only: no song-name preview and no
        // empty text, so the row keeps one stable height from the first
        // frame instead of shifting when its songs resolve.
        Mono(pl.station.name, KleeampType.rowPrimaryMedium, p.ink, maxLines = 1)
    }
}

/** A pinned, auto-populated smart playlist as a list row. */
@Composable
private fun SmartPlaylistRow(
    sp: SmartPlaylist,
    onOpen: () -> Unit,
) {
    val p = LocalPalette.current
    val kindIcon = smartKindIcon(sp.kind)
    ListRow(
        onClick = onOpen,
        verticalPadding = 8.dp,
        leading = {
            PlaylistGlyph(kindIcon, sp.label)
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Mono(
                    if (sp.stations.isEmpty()) "empty" else "${sp.stations.size} items",
                    KleeampType.meta, p.inkFaint,
                )
            }
        },
    ) {
        Mono(sp.label, KleeampType.rowPrimaryMedium, p.ink, maxLines = 1)
    }
}

/**
 * The providers entry in the pinned list: one row standing in for every
 * connected account's songs. The count reads accounts, not songs - songs
 * load when the row opens, so the row stays instant like the smart rows
 * around it.
 */
@Composable
private fun ProvidersRow(count: Int, onOpen: () -> Unit) {
    val p = LocalPalette.current
    ListRow(
        onClick = onOpen,
        verticalPadding = 8.dp,
        leading = {
            PlaylistGlyph(KleeampIcons.Server, "providers")
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Mono(
                    if (count == 0) "none yet" else "$count account${if (count == 1) "" else "s"}",
                    KleeampType.meta, p.inkFaint,
                )
            }
        },
    ) {
        Mono("providers", KleeampType.rowPrimaryMedium, p.ink, maxLines = 1)
    }
}

/** A playlist's identifying mark: static themed glyph on a plate, identical
 * in every theme. Deliberately not cover art - rows stay instant, with
 * nothing to load or decode. */
@Composable
private fun PlaylistGlyph(icon: ImageVector, contentDescription: String?) {
    GlyphPlate(icon, contentDescription, Modifier.size(44.dp))
}

/** The smart playlist's identifying glyph. */
private fun smartKindIcon(kind: SmartKind): ImageVector = when (kind) {
    SmartKind.LocalSongs -> KleeampIcons.MusicNote
    SmartKind.Downloads -> KleeampIcons.Download
    SmartKind.Favorites -> KleeampIcons.Star
    SmartKind.RecentlyPlayed -> KleeampIcons.Clock
}

/** The ⋮ overflow menu on a playlist row: pin/unpin, edit the name, or remove the playlist. */
@Composable
private fun PlaylistMenu(
    pinned: Boolean,
    onAddSongs: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(KleeampShape.small)).microPress { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                KleeampIcons.More, "menu",
                Modifier.size(17.dp),
                tint = p.inkTertiary,
            )
        }
        if (open) {
            Popup(
                onDismissRequest = { open = false },
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, 8),
            ) {
                Column(
                    Modifier.width(170.dp).clip(RoundedCornerShape(KleeampShape.small))
                        .background(p.ground).border(1.dp, p.hairlineRegion, RoundedCornerShape(KleeampShape.small)),
                ) {
                    MenuItem(if (pinned) "unpin" else "pin", p.ink, onPin) { open = false }
                    HairlineDivider(region = true)
                    MenuItem("add", p.ink, onAddSongs) { open = false }
                    HairlineDivider(region = true)
                    MenuItem("edit name", p.ink, onEdit) { open = false }
                    HairlineDivider(region = true)
                    MenuItem("remove playlist", p.destructiveInk, onDelete) { open = false }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, color: androidx.compose.ui.graphics.Color, action: () -> Unit, close: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().microPress {
            close()
            action()
        }.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mono(label, KleeampType.chip, color)
    }
}

/** A monospace, palette-styled input row for naming playlists. It never grabs
 * focus on its own: the keyboard only comes up when the user taps the field.
 * The platform IME handles entry. */
@Composable
private fun InlineNameField(
    text: String,
    placeholder: String,
    onTextChange: (String) -> Unit,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(Gutter)
            .clip(RoundedCornerShape(KleeampShape.small)).border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.small))
            .background(p.panel).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        KleeampTextField(
            value = text,
            onValueChange = { onTextChange(it.take(48)) },
            modifier = Modifier.weight(1f),
            placeholder = placeholder,
            textStyle = KleeampType.rowPrimary,
            onAction = { onDone(text) },
        )
        Mono("SAVE", KleeampType.tabLabel, p.accent,
            Modifier.clip(RoundedCornerShape(KleeampShape.tiny)).background(p.accent.copy(alpha = 0.14f))
                .microPress { onDone(text) }.padding(horizontal = 9.dp, vertical = 7.dp))
        Mono("CANCEL", KleeampType.tabLabel, p.inkTertiary,
            Modifier.clip(RoundedCornerShape(KleeampShape.tiny)).border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.tiny))
                .microPress(onClick = onCancel).padding(horizontal = 9.dp, vertical = 7.dp))
    }
}

@Composable
private fun PlaylistDetailShown(
    listState: LazyListState,
    playlist: PlaylistStore.Playlist,
    songIds: List<String>,
    members: List<Station>,
    visible: List<Station>,
    query: String,
    onQuery: (String) -> Unit,
    sort: PlaylistSort,
    onSortChange: (PlaylistSort) -> Unit,
    localSongs: List<Station>,
    radioStations: List<Station>,
    subscribedShows: List<PodcastShow>,
    showState: ShowState,
    onOpenShow: (PodcastShow) -> Unit,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onToggle: (Station) -> Unit,
    adding: Boolean,
    onAddToPlaylist: (Station) -> Unit = {},
    onRemoveMember: (Station) -> Unit = {},
    onBeginAdd: () -> Unit = {},
    favorites: Set<String> = emptySet(),
    onToggleFavorite: (Station) -> Unit = {},
    onAddToQueue: (Station) -> Unit = {},
) {
    val p = LocalPalette.current

    // Members can be any source now, so they resolve against the live local
    // library plus the persisted snapshot stations (radio/podcast members).
    // Resolved in PlaylistDetailViewModel so the pane stays dumb.
    if (adding) {
        AddSongsPicker(
            selected = songIds.toSet(),
            playlistName = playlist.station.name,
            localSongs = localSongs,
            radioStations = radioStations,
            subscribedShows = subscribedShows,
            showState = showState,
            onOpenShow = onOpenShow,
            onToggle = onToggle,
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        if (members.isEmpty()) {
            item {
                SectionLabel("songs — 0") {
                    AddSongsButton(onClick = onBeginAdd)
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Mono("empty — tap +", KleeampType.rowSecondary, p.inkFaint)
                }
            }
        } else {
            // Picker row scrolls with the list, on top of the filter; only
            // the header is fixed.
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = Gutter, end = Gutter, top = 10.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    PlaylistSort.entries.forEach { t ->
                        Chip(t.label, sort == t, onClick = { onSortChange(t) })
                    }
                }
            }
            item { FilterRow(value = query, onValue = onQuery) }
            item {
                SectionLabel("songs — ${visible.size}") {
                    AddSongsButton(onClick = onBeginAdd)
                }
            }
            if (visible.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Mono("nothing matches", KleeampType.rowSecondary, p.inkFaint)
                    }
                }
            }
            items(visible, key = { it.id }) { s ->
                ListRow(
                    rail = current?.url == s.url,
                    onClick = { onPlay(s, visible) },
                    verticalPadding = 9.dp,
                    leading = {
                        SongCover(s = s, current = current, playing = playing)
                    },
                    onQueue = { onAddToQueue(s) },
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                                    OverflowMenu(
                                        trigger = { open -> OverflowButton(open, size = 16) },
                                        items = listOf(
                                            OverflowItem("add to playlist", color = p.ink, action = { onAddToPlaylist(s) }),
                                            OverflowItem("drop", color = p.destructiveInk, action = { onRemoveMember(s) }),
                                        ),
                                    )
                            Icon(
                                if (s.url in favorites) KleeampIcons.StarFilled else KleeampIcons.Star,
                                "favourite",
                                Modifier.size(15.dp).microPress { onToggleFavorite(s) },
                                tint = if (s.url in favorites) p.accent else p.inkFaint,
                            )
                        }
                    },
                ) {
                    Mono(s.name, KleeampType.rowPrimary, if (current?.url == s.url) p.accent else p.ink, maxLines = 1)
                    Mono(
                        s.artistAlbum.ifBlank {
                            when {
                                s.source == StationSource.Local -> durationLabel(s.durationMs)
                                s.source == StationSource.Podcast -> "podcast"
                                else -> s.meta
                            }
                        },
                        KleeampType.rowSecondary, p.inkTertiary, maxLines = 1,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

private enum class AddTab(val label: String) { Local("local"), Stations("stations"), Podcasts("podcasts") }

/** The boxed + that opens a song picker, shared by every songs section. */
@Composable
private fun AddSongsButton(onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .background(if (p.dark) p.keyFace else p.ground)
            .border(1.dp, p.keyBorder, RoundedCornerShape(KleeampShape.small))
            .microPress(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(KleeampIcons.Plus, "add songs", Modifier.size(16.dp), tint = p.accent)
    }
}

/**
 * A song picker over local songs, radio stations and podcast episodes in
 * their own sub-tabs, with one shared selection carried across all of them.
 * Every tap writes through immediately; leaving (back or another page) keeps
 * everything, so there is no done control.
 */
@Composable
private fun AddSongsPicker(
    selected: Set<String>,
    playlistName: String,
    localSongs: List<Station>,
    radioStations: List<Station>,
    subscribedShows: List<PodcastShow>,
    showState: ShowState,
    onOpenShow: (PodcastShow) -> Unit,
    onToggle: (Station) -> Unit,
) {
    val p = LocalPalette.current
    var tab by remember { mutableStateOf(AddTab.Local) }
    var query by remember { mutableStateOf("") }
    var openShow by remember { mutableStateOf<PodcastShow?>(null) }

    // Taps carry the song only: direction resolves store-side from fresh
    // state, so a tap can never add-then-remove on a stale snapshot.
    // Checked state still reads the live membership, so rotation or a write
    // from elsewhere can never desync the boxes from the database.

    Column(Modifier.fillMaxSize()) {
        Mono(
            playlistName, KleeampType.chip, p.accent, maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = Gutter, end = Gutter, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            AddTab.entries.forEach { t -> Chip(t.label, tab == t, onClick = { tab = t; query = "" }) }
        }
        HairlineDivider(region = true)

        when (tab) {
            AddTab.Local -> GroupList(
                items = localSongs.matching(query),
                query = query,
                onQuery = { query = it },
                picked = selected,
                subtitle = { s ->
                    s.artistAlbum.ifBlank { durationLabel(s.durationMs) }
                },
                onToggle = onToggle,
                empty = "no local songs yet",
            )
            AddTab.Stations -> GroupList(
                items = radioStations.matching(query),
                query = query,
                onQuery = { query = it },
                picked = selected,
                subtitle = { s -> s.meta },
                onToggle = onToggle,
                empty = "no stations to add",
            )
            AddTab.Podcasts -> PodcastGroups(
                shows = subscribedShows,
                query = query,
                onQuery = { query = it },
                openShow = openShow,
                onOpenShow = { show ->
                    openShow = show
                    onOpenShow(show)
                },
                onBackToShows = { openShow = null },
                showState = showState,
                picked = selected,
                onToggle = onToggle,
            )
        }
    }
}

@Composable
private fun GroupList(
    items: List<Station>,
    query: String,
    onQuery: (String) -> Unit,
    picked: Set<String>,
    subtitle: (Station) -> String,
    onToggle: (Station) -> Unit,
    empty: String,
) {
    val p = LocalPalette.current
    LazyColumn(Modifier.fillMaxSize()) {
        item { FilterRow(value = query, onValue = onQuery) }
        if (items.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Mono(empty, KleeampType.rowSecondary, p.inkFaint)
                }
            }
        } else {
            items(items, key = { it.id }) { s ->
                val inPl = s.id in picked
                ListRow(
                    onClick = { onToggle(s) },
                    verticalPadding = 8.dp,
                    leading = {
                        Box(
                            Modifier.size(24.dp).clip(RoundedCornerShape(KleeampShape.tiny))
                                .then(if (inPl) Modifier.background(p.accent)
                                      else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.tiny))),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (inPl) Icon(KleeampIcons.Check, null, Modifier.size(10.dp), tint = p.onAccent)
                        }
                    },
                ) {
                    Mono(s.name, KleeampType.rowPrimary, p.ink, maxLines = 1)
                    Mono(subtitle(s), KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun PodcastGroups(
    shows: List<PodcastShow>,
    query: String,
    onQuery: (String) -> Unit,
    openShow: PodcastShow?,
    onOpenShow: (PodcastShow) -> Unit,
    onBackToShows: () -> Unit,
    showState: ShowState,
    picked: Set<String>,
    onToggle: (Station) -> Unit,
) {
    val p = LocalPalette.current
    val show = openShow
    val q = query.trim().lowercase()
    if (show != null) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { FilterRow(value = query, onValue = onQuery) }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = Gutter, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Chip("‹ shows", selected = false, onClick = onBackToShows)
                    Spacer(Modifier.width(2.dp))
                    Mono(show.title, KleeampType.chip, p.ink, maxLines = 1)
                }
            }
            if (showState.loading) {
                item { Mono("loading episodes…", KleeampType.rowSecondary, p.inkFaint, Modifier.padding(horizontal = Gutter, vertical = 12.dp)) }
            } else {
                val eps = showState.episodes.filter {
                    it.isFull && (q.isBlank() || it.title.lowercase().contains(q))
                }
                items(eps, key = { "pod:${show.id}:${it.guid}" }) { e ->
                    val s = e.toStation(show)
                    val inPl = s.id in picked
                    ListRow(
                        onClick = { onToggle(s) },
                        verticalPadding = 8.dp,
                        leading = {
                            Box(
                                Modifier.size(24.dp).clip(RoundedCornerShape(KleeampShape.tiny))
                                    .then(if (inPl) Modifier.background(p.accent)
                                          else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.tiny))),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (inPl) Icon(KleeampIcons.Check, null, Modifier.size(10.dp), tint = p.onAccent)
                            }
                        },
                    ) {
                        Mono(s.name, KleeampType.rowPrimary, p.ink, maxLines = 1)
                        Mono(durationLabel(s.durationMs).ifBlank { "episode" }, KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
                    }
                }
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { FilterRow(value = query, onValue = onQuery) }
        if (shows.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Mono("no subscribed podcasts yet", KleeampType.rowSecondary, p.inkFaint)
                }
            }
        } else {
            val shown = if (q.isBlank()) shows
            else shows.filter {
                it.title.lowercase().contains(q) || it.author.lowercase().contains(q)
            }
            items(shown, key = { it.feedUrl }) { show ->
                ListRow(
                    onClick = { onOpenShow(show) },
                    verticalPadding = 9.dp,
                    trailing = { Icon(KleeampIcons.CaretRight, "open", Modifier.size(11.dp), tint = p.inkTertiary) },
                ) {
                    Mono(show.title, KleeampType.rowPrimary, p.ink, maxLines = 1)
                    Mono(show.meta, KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** Detail view for a pinned smart playlist: every member station, local and radio. */
@Composable
private fun SmartPlaylistDetail(
    listState: LazyListState,
    pl: SmartPlaylist,
    members: List<Station>,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onToggleFavorite: (Station) -> Unit,
    favorites: Set<String>,
    loading: Boolean = false,
    favScope: FavScope = FavScope.All,
    onFavScopeChange: (FavScope) -> Unit = {},
    onSortChange: (PlaylistSort) -> Unit = {},
    folders: List<SongFolder> = emptyList(),
    folder: String? = null,
    onFolder: (String?) -> Unit = {},
    onInfo: (Station) -> Unit = {},
    onRemove: (Station) -> Unit = {},
    progress: Map<String, EpisodeProgress> = emptyMap(),
    showResume: Boolean = false,
    sort: PlaylistSort = PlaylistSort.Title,
    fetchedBytes: Map<String, Long> = emptyMap(),
    onAddToPlaylist: (Station) -> Unit = {},
    onAddToQueue: (Station) -> Unit = {},
    /** Non-null on lists that can grow: renders the section + button. */
    onBeginAdd: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    // Only the on-device smart lists sort; favourites and recent have their
    // own fixed orders (recent is already time-sorted). Local songs arrive
    // already folder-filtered when a folder chip is active.
    val local = pl.kind == SmartKind.LocalSongs || pl.kind == SmartKind.Downloads
    // Favourites mix local songs, radio stations and podcasts, so they get
    // their own type sub-tabs: all / local / stations / podcasts.
    val isFav = pl.kind == SmartKind.Favorites
    val visible = remember(members, local, sort, isFav, favScope) {
        val base = if (local) sortedStations(members, sort) else members
        if (!isFav || favScope == FavScope.All) base
        else base.filter { s ->
            when (favScope) {
                FavScope.Local -> s.source == StationSource.Local
                FavScope.Stations -> s.source != StationSource.Local && s.source != StationSource.Podcast
                FavScope.Pods -> s.source == StationSource.Podcast
                FavScope.All -> true
            }
        }
    }
    var query by rememberSaveable(pl.key) { mutableStateOf("") }
    val shown = remember(visible, query) { visible.matching(query) }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        // Picker rows scroll with the list, on top of the filter; only the
        // header is fixed.
        if (isFav) {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = Gutter, end = Gutter, top = 10.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    FavScope.entries.forEach { f ->
                        Chip(f.label, favScope == f, onClick = { onFavScopeChange(f) })
                    }
                }
            }
        }
        if (local) {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = Gutter, end = Gutter, top = 10.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistSort.entries.forEach { t ->
                        Chip(t.label, sort == t, onClick = { onSortChange(t) })
                    }
                    // Local songs only: the folder picker trails the sort row.
                    if (pl.kind == SmartKind.LocalSongs && folders.isNotEmpty()) {
                        ChipDropdown(
                            label = folders.firstOrNull { it.path == folder }?.name ?: "all folders",
                            selected = folder != null,
                            options = listOf(
                                ChipOption("all folders") { onFolder(null) },
                            ) + folders.map { f ->
                                ChipOption(f.name) { onFolder(f.path) }
                            },
                        )
                    }
                }
            }
        }
        item { FilterRow(value = query, onValue = { query = it }) }
        if (shown.isEmpty()) {
            // Growable lists keep their + on empty too, like playlists do.
            if (onBeginAdd != null) {
                item {
                    SectionLabel("${pl.label} — 0") {
                        AddSongsButton(onClick = onBeginAdd)
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Mono(
                        when {
                            query.isNotBlank() -> "nothing matches"
                            pl.kind == SmartKind.Favorites && favScope == FavScope.Local -> "no local favourites yet"
                            pl.kind == SmartKind.Favorites && favScope == FavScope.Stations -> "no station favourites yet"
                            pl.kind == SmartKind.Favorites && favScope == FavScope.Pods -> "no podcast favourites yet"
                            pl.kind == SmartKind.LocalSongs ->
                                if (loading) "scanning for songs…" else "no songs on the phone yet"
                            pl.kind == SmartKind.Downloads ->
                                if (loading) "scanning for songs…" else "no downloads yet"
                            pl.kind == SmartKind.Favorites -> "no favourites yet"
                            else -> "nothing played recently"
                        },
                        KleeampType.rowSecondary, p.inkFaint,
                    )
                }
            }
        } else {
            item {
                SectionLabel("${pl.label} — ${shown.size}") {
                    onBeginAdd?.let { AddSongsButton(onClick = it) }
                }
            }
            items(shown, key = { it.url }, contentType = { "local-song" }) { s ->
                ListRow(
                    rail = current?.url == s.url,
                    onClick = { onPlay(s, shown) },
                    verticalPadding = 9.dp,
                    leading = {
                        SongCover(s = s, current = current, playing = playing)
                    },
                    onQueue = { onAddToQueue(s) },
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // ⋮ menu 12dp left of the star, like episode rows:
                            // only on the on-device lists, favourites and
                            // recently-played are read-only views.
                            if (local) {
                                OverflowMenu(
                                    trigger = { open -> OverflowButton(open, size = 16) },
                                    items = listOf(
                                        OverflowItem("add to playlist", color = p.ink, action = { onAddToPlaylist(s) }),
                                        OverflowItem("info", color = p.ink, action = { onInfo(s) }),
                                        OverflowItem("remove", color = p.destructiveInk, action = { onRemove(s) }),
                                    ),
                                )
                            }
                            Icon(
                                if (s.url in favorites) KleeampIcons.StarFilled else KleeampIcons.Star,
                                "favourite",
                                Modifier.size(15.dp).microPress { onToggleFavorite(s) },
                                tint = if (s.url in favorites) p.accent else p.inkFaint,
                            )
                        }
                    },
                ) {
                    Mono(s.name, KleeampType.rowPrimary, if (current?.url == s.url) p.accent else p.ink, maxLines = 1)
                    // Local rows wear their saved position like podcast
                    // episodes do, but only while resume is switched on.
                    val resumed = if (local && showResume) {
                        progress[s.url]?.takeIf { !it.completed && it.positionMs > 0 }
                    } else null
                    Mono(
                        buildList {
                            when (s.source) {
                                StationSource.Podcast -> add(s.artist.ifBlank { s.meta.ifBlank { "podcast" } })
                                StationSource.Local -> add(s.artistAlbum.ifBlank { s.meta })
                                else -> {
                                    s.meta.takeIf { it.isNotBlank() }?.let { add(it) }
                                    s.tagList.take(2).forEach { add(it) }
                                }
                            }
                            resumed?.let { add("${(it.fraction * 100).toInt()}% in") }
                            if (pl.kind == SmartKind.Downloads) {
                                fetchedBytes[s.url]?.let { add(downloadSizeLabel(it)) }
                            }
                        }.joinToString(" · "),
                        KleeampType.rowSecondary, if (resumed != null) p.amber else p.inkTertiary, maxLines = 1,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

/**
 * A playlist row's leading thumbnail: the item's exact cover when it has one,
 * with a small play/pause badge overlaid when it is the current track. The
 * cover is resolved the way each source's home screen resolves it - embedded
 * art for local files, the known artwork URL for episodes and provider
 * tracks, branding discovery for radio - so a row never shows a
 * generic note where its home shows real art. Coverless rows wear their
 * home placeholder: the show's PodRow, the themed plate (note for local
 * files and provider tracks, broadcast mark for live stations).
 *
 * Shared with folder rows so they wear exactly what list rows wear.
 */
@Composable
internal fun SongCover(s: Station, current: Station?, playing: Boolean) {
    val p = LocalPalette.current
    val art = rememberStationThumbnail(s)
    val active = current?.url == s.url
    // Coverless local files, provider tracks and live stations wear the
    // themed plate - the same accent glyph plate the playlist rows wear -
    // instead of a faint outline box, so the fallback follows the theme
    // like everything else.
    if (art == null && (s.source == StationSource.Local || s.source == StationSource.Provider || !s.isTrack)) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            GlyphPlate(
                if (s.isTrack) KleeampIcons.MusicNote else KleeampIcons.StationsTab,
                s.name,
                Modifier.size(40.dp),
            )
            if (active) CoverBadge(playing)
        }
        return
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .then(
                if (art != null) Modifier.background(p.panel)
                else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.small))
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, s.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            // Coverless episodes keep their home mark in the row box; local
            // files, provider tracks and stations take the music mark -
            // never a play glyph. Play state keeps its badge below.
            Icon(
                when (s.source) {
                    StationSource.Podcast -> KleeampIcons.PodRow
                    else -> KleeampIcons.MusicNote
                },
                null,
                Modifier.size(if (s.source == StationSource.Podcast) 18.dp else 15.dp),
                tint = if (s.source == StationSource.Podcast) p.inkFaint else p.inkTertiary,
            )
        }
        if (active) CoverBadge(playing)
    }
}

/** The small accent play/pause badge worn over a current track's cover. */
@Composable
private fun CoverBadge(playing: Boolean) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(KleeampShape.tiny))
            .background(p.accent.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) KleeampIcons.Pause else KleeampIcons.PlayRow,
            null,
            Modifier.size(9.dp),
            tint = p.onAccent,
        )
    }
}

/**
 * Full-bleed song detail reached from a song row's ⋮ → "info": the cover art
 * up top with every scrap of metadata, a favourite toggle, and the destructive
 * "remove from device" action. It overlays the Library tab (own back handler)
 * instead of being pushed into the row-scroll stack.
 */
@Composable
private fun SongInfoView(
    s: Station,
    systemBack: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: (Station) -> Unit,
    favorite: Boolean,
    onRemove: () -> Unit,
    plays: Int = 0,
    lastPlayedAt: Long = 0L,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var art by remember(s.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(s.id) {
        art = (LocalArt.bitmapFor(s.cover, context.contentResolver)
            ?: StationArtSource.bitmapFor(s))?.asImageBitmap()
    }

    // The overlay handles its own back press (it is not part of the list pane's
    // navigation), but must stand down when a player/queue overlay is on top.
    BackHandler(enabled = systemBack) { onDismiss() }

    val path = s.url.removePrefix("file://").let(Uri::decode)
    val sizeLabel = remember(s.url) {
        val f = File(path)
        val mb = f.length() / 1_048_576f
        String.format(Locale.US, "%.1f MB", mb)
    }
    val added = remember(s.dateAdded) {
        if (s.dateAdded > 0) SimpleDateFormat("dd MMM yyyy", Locale.US)
            .format(Date(s.dateAdded * 1000L)) else "—"
    }

    Box(Modifier.fillMaxSize().background(p.ground)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader {
                Row(
                    Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackChevron(onDismiss)
                    Mono("Info", KleeampType.screenTitle, p.ink, maxLines = 1)
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ArtGlow(Modifier.size(280.dp))
                    Box(
                        Modifier.size(280.dp)
                            .shadow(26.dp, RoundedCornerShape(KleeampShape.large))
                            .clip(RoundedCornerShape(KleeampShape.large)).background(p.artB),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (art != null) {
                            Image(art!!, s.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(KleeampIcons.MusicNote, null, Modifier.size(40.dp), tint = p.inkTertiary)
                        }
                        if (favorite) {
                            Mono(
                                "♥ favourite", KleeampType.chip, p.onAccent,
                                Modifier.align(Alignment.TopStart).padding(8.dp)
                                    .clip(RoundedCornerShape(KleeampShape.tiny)).background(p.accent.copy(alpha = 0.92f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                Column(Modifier.padding(horizontal = Gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Mono(s.name, KleeampType.trackTitleCompact, p.ink, maxLines = 2)
                    Mono(s.artist.ifBlank { "unknown artist" }, KleeampType.rowSecondary, p.inkTertiary)
                    if (s.album.isNotBlank()) Mono(s.album, KleeampType.body, p.inkTertiary)
                }

                Column(
                    Modifier.fillMaxWidth().padding(Gutter),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    SongInfoRow("duration", durationLabel(s.durationMs))
                    SongInfoRow("added", added)
                    SongInfoRow("plays", if (plays > 0) "$plays" else "—")
                    SongInfoRow(
                        "last played",
                        if (lastPlayedAt > 0) SimpleDateFormat("dd MMM yyyy", Locale.US)
                            .format(Date(lastPlayedAt)) else "—",
                    )
                    SongInfoRow("size", sizeLabel)
                    SongInfoRow("format", File(path).extension.uppercase().ifBlank { "—" })
                    SongInfoRow("location", File(path).parent.orEmpty())
                }

                Spacer(Modifier.height(20.dp))

                // Actions: favourite toggle and destructive remove, side by side.
                Row(Modifier.fillMaxWidth().padding(Gutter)) {
                    Row(
                        Modifier.padding(end = 4.dp).weight(1f).clip(RoundedCornerShape(KleeampShape.small)).background(p.panel)
                            .microPress { onToggleFavorite(s) }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (favorite) KleeampIcons.StarFilled else KleeampIcons.Star,
                            "favourite",
                            Modifier.size(15.dp).padding(end = 6.dp),
                            tint = if (favorite) p.accent else p.inkTertiary,
                        )
                        Mono(if (favorite) "favourited" else "favourite", KleeampType.chip, p.ink)
                    }
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(KleeampShape.small)).background(p.panel)
                            .microPress { onRemove() }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Mono("remove from device", KleeampType.chip, p.destructiveInk)
                    }
                }

                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun SongInfoRow(label: String, value: String) {
    val p = LocalPalette.current
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Mono(label, KleeampType.meta, p.inkFaint, Modifier.width(80.dp))
            Mono(value, KleeampType.rowSecondary, p.ink, maxLines = 2)
        }
        HairlineDivider()
    }
}
