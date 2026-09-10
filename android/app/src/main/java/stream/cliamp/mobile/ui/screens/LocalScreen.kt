package stream.cliamp.mobile.ui.screens

import android.Manifest
import android.app.Activity
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import stream.cliamp.mobile.CliampApp
import stream.cliamp.mobile.data.LocalArt
import stream.cliamp.mobile.data.StationArtSource
import stream.cliamp.mobile.data.LocalLibrary
import stream.cliamp.mobile.data.PlaylistStore
import stream.cliamp.mobile.data.PodcastRepository
import stream.cliamp.mobile.data.PodcastShow
import stream.cliamp.mobile.data.Repository
import stream.cliamp.mobile.data.ShowState
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.DirectoryState
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.data.toStation
import stream.cliamp.mobile.data.PlaylistSort
import stream.cliamp.mobile.data.durationLabel
import stream.cliamp.mobile.data.sortedStations
import stream.cliamp.mobile.data.provider.ProviderAccount
import stream.cliamp.mobile.data.provider.ProviderCatalog
import stream.cliamp.mobile.data.provider.ProviderSpec
import stream.cliamp.mobile.ui.components.BackChip
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.CliampTextField
import stream.cliamp.mobile.ui.components.GridListToggle
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.OverflowButton
import stream.cliamp.mobile.ui.components.OverflowItem
import stream.cliamp.mobile.ui.components.OverflowMenu
import stream.cliamp.mobile.ui.components.ScreenHeader
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.components.scrollToTop
import stream.cliamp.mobile.ui.components.ArtGlow
import stream.cliamp.mobile.ui.components.ArtPlate
import stream.cliamp.mobile.ui.components.MainLayout
import stream.cliamp.mobile.ui.components.microPress
import stream.cliamp.mobile.ui.theme.CliampShape
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

/** The pinned, auto-populated smart playlists on the library tab. */
enum class SmartKind(val label: String) {
    LocalSongs("local songs"),
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
    localLibrary: LocalLibrary,
    playlists: PlaylistStore,
    favorites: List<Station>,
    recent: List<Station>,
    onOpenProviders: () -> Unit = {},
    onOpenSmart: (String) -> Unit = {},
    onOpenPlaylist: (String) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    favScope: FavScope = FavScope.All,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var creatingName by rememberSaveable { mutableStateOf(false) }
    var renamingSlug by rememberSaveable { mutableStateOf<String?>(null) }
    var nameText by rememberSaveable { mutableStateOf("") }

    val songs by localLibrary.songs.collectAsState()
    val loading by localLibrary.loading.collectAsState()
    val libError by localLibrary.error.collectAsState()
    val allPlaylists by playlists.playlists.collectAsState(initial = emptyList())
    val pinnedSlugs by playlists.pinnedSlugs.collectAsState(initial = emptySet())
    val pinnedPlaylists = allPlaylists.filter { it.station.slug in pinnedSlugs }
    val unpinnedPlaylists = allPlaylists.filterNot { it.station.slug in pinnedSlugs }

    val audioPerm = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    var haveAudio by remember { mutableStateOf(checkAudio(context, audioPerm)) }

    // The media permission is requested once at app launch. Recheck it whenever
    // this screen resumes, so granting it in system Settings (without another
    // in-app dialog) is picked up here.
    LifecycleResumeEffect(Unit) {
        haveAudio = checkAudio(context, audioPerm)
        onPauseOrDispose { }
    }

    LaunchedEffect(haveAudio) {
        if (haveAudio) localLibrary.refresh()
    }

    val filtered = songs
    // Playing starts minimized; the full player only opens when the mini-player
    // bar at the bottom is tapped.

    // The favourites sub-tab is chosen while browsing the list; the favourite's
    // pinned tile collage follows it, so the covers preview the filtered scope.
    // Hoisted to the navigation owner so the list and the smart detail pane
    // stay on the same scope.
    val prefs = (context.applicationContext as CliampApp).prefs
    val localSort by prefs.playlistSort("local-songs")
        .collectAsState(initial = prefs.playlistSortValue("local-songs"))
    // Grid/list layout for each playlist section, remembered on disk.
    val pinnedGrid by prefs.pinnedGrid.collectAsState(initial = prefs.pinnedGrid.value)
    val playlistsGrid by prefs.playlistsGrid.collectAsState(initial = prefs.playlistsGrid.value)

    // Pinned smart playlists — auto-populated from global state, non-removable.
    // "local songs" is always at the very top. Their tile collages preview the
    // first four members *exactly as the detail pane lists them*, i.e. under
    // the same sort (local songs) and type filter (favourites) the user chose.
    val smartPlaylists = remember(filtered, favorites, recent, localSort, favScope) {
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
            SmartPlaylist(SmartKind.LocalSongs, local),
            SmartPlaylist(SmartKind.Favorites, favs),
            SmartPlaylist(SmartKind.RecentlyPlayed, recent),
        )
    }
    // ---- base page (always composed) ----
    Box(
        Modifier
            .fillMaxSize()
            .background(p.ground),
    ) {
    // Hoisted above the remounting grid so a grid/list toggle keeps position.
    val libraryGridState = rememberLazyGridState()
    MainLayout(
            title = "Library",
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(libraryGridState) },
            chips = {
                Chip("playlists", selected = true, onClick = {})
                Chip("providers", selected = false, onClick = onOpenProviders)
            },
        ) {

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !haveAudio -> PermissionNote()
                libError != null && songs.isEmpty() -> CenterNote(libError!!, p.destructiveInk)
                else -> PlaylistList(
                    gridState = libraryGridState,
                    smart = smartPlaylists,
                    pinnedPlaylists = pinnedPlaylists,
                    playlists = unpinnedPlaylists,
                    songs = songs,
                    creating = creatingName,
                    renamingSlug = renamingSlug,
                    editText = nameText,
                    onEditTextChange = { nameText = it },
                    onCreate = { name -> scope.launch { playlists.create(name) }; creatingName = false },
                    onBeginCreate = { creatingName = true; nameText = "" },
                    onCancel = { creatingName = false; renamingSlug = null },
                    onRename = { slug, name ->
                        scope.launch { playlists.rename(slug, name) }
                        renamingSlug = null
                    },
                    onBeginRename = { slug ->
                        renamingSlug = slug
                        nameText = allPlaylists.firstOrNull { it.station.slug == slug }?.station?.name.orEmpty()
                    },
                    onDelete = { slug ->
                        scope.launch { playlists.delete(slug) }
                        if (renamingSlug == slug) renamingSlug = null
                    },
                    onAddSongs = { slug -> onOpenPlaylist(slug) },
                    onPin = { slug, pinned -> scope.launch { playlists.setPinned(slug, pinned) } },
                    onOpen = { onOpenPlaylist(it.station.slug) },
                    onOpenSmart = { onOpenSmart(it.kind.name) },
                    pinnedGrid = pinnedGrid,
                    onTogglePinnedGrid = { scope.launch { prefs.setPinnedGrid(!pinnedGrid) } },
                    playlistsGrid = playlistsGrid,
                    onTogglePlaylistsGrid = { scope.launch { prefs.setPlaylistsGrid(!playlistsGrid) } },
                    loading = loading,
                )
            }
        }
        }
    }
}

/**
 * Shared delete helper: hands the file to the OS delete sheet (or drops it
 * directly on old Android). Only the confirmed case drops the song.
 */
@Composable
private fun rememberRemoveLocalSong(
    localLibrary: LocalLibrary,
    onGone: (Station) -> Unit = {},
): (Station) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = (context.applicationContext as CliampApp).prefs
    val favorites by prefs.favorites.collectAsState(initial = emptyList())
    val favoriteUrls = remember(favorites) { favorites.mapTo(HashSet()) { it.url } }
    var pendingDelete by remember { mutableStateOf<Station?>(null) }
    val dropLocal: (Station) -> Unit = { s ->
        scope.launch {
            localLibrary.removeLocal(s)
            if (s.url in favoriteUrls) prefs.removeFavorite(s)
            onGone(s)
        }
    }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        pendingDelete?.let { s ->
            if (res.resultCode == Activity.RESULT_OK) dropLocal(s)
            pendingDelete = null
        }
    }
    return remember(localLibrary) {
        { s: Station ->
            val start = localLibrary.deleteRequest(s)
            if (start != null) {
                pendingDelete = s
                deleteLauncher.launch(IntentSenderRequest.Builder(start).build())
            } else {
                dropLocal(s)
            }
        }
    }
}

/** Providers as a navigation pane: connected accounts, then every addable type. */
@Composable
fun LibraryProvidersPane(
    providers: List<ProviderAccount>,
    onBack: () -> Unit,
    onOpenProvider: (ProviderAccount) -> Unit,
    onAddProvider: (ProviderSpec) -> Unit,
    onRemoveProvider: (ProviderAccount) -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(p.ground)) {
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        MainLayout(
            title = "providers",
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(listState) },
            chips = {
                BackChip(onClick = onBack)
            },
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ProvidersView(
                    listState = listState,
                    providers = providers,
                    onOpenProvider = onOpenProvider,
                    onAddProvider = onAddProvider,
                    onRemoveProvider = onRemoveProvider,
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
    kindName: String,
    localLibrary: LocalLibrary,
    current: Station?,
    playing: Boolean,
    favorites: List<Station>,
    recent: List<Station>,
    onPlay: (Station, List<Station>) -> Unit,
    onToggleFavorite: (Station) -> Unit,
    favScope: FavScope = FavScope.All,
    onFavScopeChange: (FavScope) -> Unit = {},
    onOpenSongInfo: (Station) -> Unit = {},
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val kind = remember(kindName) {
        SmartKind.entries.firstOrNull { it.name == kindName }
    }
    val songs by localLibrary.songs.collectAsState()
    val loading by localLibrary.loading.collectAsState()
    val appPrefs = (context.applicationContext as CliampApp).prefs
    val localSort by appPrefs.playlistSort("local-songs")
        .collectAsState(initial = appPrefs.playlistSortValue("local-songs"))
    val removeLocalSong = rememberRemoveLocalSong(localLibrary)
    val smartPlaylists = remember(songs, favorites, recent, localSort, favScope) {
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
            SmartPlaylist(SmartKind.LocalSongs, local),
            SmartPlaylist(SmartKind.Favorites, favs),
            SmartPlaylist(SmartKind.RecentlyPlayed, recent),
        )
    }
    val pl = smartPlaylists.firstOrNull { it.kind == kind }
    Box(Modifier.fillMaxSize().background(p.ground)) {
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        MainLayout(
            title = pl?.label ?: "playlist",
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(listState) },
            chips = {
                BackChip(onClick = onBack)
            },
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (pl == null) {
                    CenterNote("no such playlist", p.inkTertiary)
                } else {
                    SmartPlaylistDetail(
                        listState = listState,
                        pl = pl,
                        current = current,
                        playing = playing,
                        onPlay = onPlay,
                        onToggleFavorite = onToggleFavorite,
                        favorites = favorites.map { it.url }.toSet(),
                        loading = loading,
                        favScope = favScope,
                        onFavScopeChange = onFavScopeChange,
                        onInfo = onOpenSongInfo,
                        onRemove = removeLocalSong,
                    )
                }
            }
        }
    }
}

/** One user playlist as a navigation pane, with add-songs and cover editing. */
@Composable
fun LibraryPlaylistPane(
    slug: String,
    localLibrary: LocalLibrary,
    playlists: PlaylistStore,
    repository: Repository,
    podcasts: PodcastRepository,
    current: Station?,
    playing: Boolean,
    favorites: List<Station>,
    onPlay: (Station, List<Station>) -> Unit,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var adding by rememberSaveable(slug) { mutableStateOf(false) }
    val songs by localLibrary.songs.collectAsState()
    val allPlaylists by playlists.playlists.collectAsState(initial = emptyList())
    val subscriptions by podcasts.subscriptions.collectAsState(initial = emptyList())
    val cliamp by repository.cliamp.collectAsState(initial = emptyList())
    val directory by repository.directory.collectAsState(initial = DirectoryState())
    val radioStations = remember(cliamp, directory.stations, favorites) {
        val favRadio = favorites.filterNot {
            it.source == StationSource.Local || it.source == StationSource.Podcast
        }
        (cliamp + directory.stations + favRadio).distinctBy { it.id }
    }
    val pl = allPlaylists.firstOrNull { it.station.slug == slug }
    val coverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val cover = uri?.toString().orEmpty()
        if (cover.isNotBlank()) scope.launch { playlists.setCover(slug, cover) }
    }
    Box(Modifier.fillMaxSize().background(p.ground)) {
        val listState = rememberLazyListState()
        MainLayout(
            title = pl?.station?.name ?: "playlist",
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onTitleClick = { scope.scrollToTop(listState) },
            chips = {
                BackChip(onClick = onBack)
                if (pl != null) {
                    Chip("add", selected = false, onClick = { adding = true })
                    Chip("set cover", selected = false, onClick = { coverLauncher.launch("image/*") })
                }
            },
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (pl == null) {
                    CenterNote("playlist gone", p.inkTertiary)
                } else {
                    PlaylistDetailShown(
                        listState = listState,
                        playlist = pl,
                        songIds = pl.songIds,
                        playlists = playlists,
                        localSongs = songs,
                        radioStations = radioStations,
                        podcasts = podcasts,
                        subscribedShows = subscriptions,
                        current = current,
                        playing = playing,
                        onPlay = onPlay,
                        onToggle = { s, add ->
                            scope.launch {
                                if (add) playlists.addStation(pl.station.slug, s)
                                else playlists.removeSong(pl.station.slug, s.id)
                            }
                        },
                        adding = adding,
                        doneAdding = { adding = false },
                    )
                }
            }
        }
    }
}

/** One song's full detail as a navigation pane. */
@Composable
fun LibrarySongInfoPane(
    stationUrl: String,
    localLibrary: LocalLibrary,
    repository: Repository,
    favorites: List<Station>,
    recent: List<Station>,
    onToggleFavorite: (Station) -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val songs by localLibrary.songs.collectAsState()
    val cliamp by repository.cliamp.collectAsState(initial = emptyList())
    val directory by repository.directory.collectAsState(initial = DirectoryState())
    val station = remember(stationUrl, songs, favorites, recent, cliamp, directory) {
        (songs + favorites + recent + cliamp + directory.stations)
            .distinctBy { it.url }
            .firstOrNull { it.url == stationUrl }
    }
    val removeLocalSong = rememberRemoveLocalSong(localLibrary) { onBack() }
    Box(Modifier.fillMaxSize().background(p.ground)) {
        if (station == null) {
            CenterNote("song gone", p.inkTertiary)
        } else {
            SongInfoView(
                s = station,
                systemBack = false,
                onDismiss = onBack,
                onToggleFavorite = onToggleFavorite,
                favorite = station.url in favorites.map { it.url }.toSet(),
                onRemove = { removeLocalSong(station) },
            )
        }
    }
}

private fun checkAudio(context: android.content.Context, perm: String): Boolean =
    context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED

@Composable
private fun CenterNote(text: String, color: androidx.compose.ui.graphics.Color) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Mono(text, CliampType.rowSecondary, color)
    }
}

@Composable
private fun PermissionNote() {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(Gutter)) {
        Spacer(Modifier.height(12.dp))
        Mono("this app needs to read your audio files to show them here.", CliampType.rowPrimary, p.ink)
        Spacer(Modifier.height(4.dp))
        Mono("grant media access in the app's settings to see your local songs.", CliampType.rowSecondary, p.inkTertiary)
        Spacer(Modifier.height(12.dp))
        HairlineDivider()
    }
}

@Composable
private fun PlaylistList(
    gridState: LazyGridState,
    smart: List<SmartPlaylist>,
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
    pinnedGrid: Boolean = true,
    onTogglePinnedGrid: () -> Unit = {},
    playlistsGrid: Boolean = false,
    onTogglePlaylistsGrid: () -> Unit = {},
    loading: Boolean = false,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        // Remount the grid whenever a section flips grid/list: LazyGrid caches
        // measured item spans, so mutating them in place (toggle + long list)
        // hits the known androidx "Place was called on a node which was placed
        // already" crash. A fresh grid keeps spans constant for its lifetime.
        key(pinnedGrid, playlistsGrid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = gridState,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val pinnedCount = smart.size + pinnedPlaylists.size
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionLabel("pinned — $pinnedCount") {
                    if (!creating) {
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            GridListToggle(gridMode = pinnedGrid, onToggle = onTogglePinnedGrid)
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(CliampShape.small))
                                    .background(if (p.dark) p.keyFace else p.ground)
                                    .border(1.dp, p.keyBorder, RoundedCornerShape(CliampShape.small))
                                    .microPress(onClick = onBeginCreate),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(CliampIcons.Plus, "new playlist", Modifier.size(16.dp), tint = p.accent)
                            }
                        }
                    }
                }
            }
            if (creating) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    InlineNameField(
                        text = editText,
                        onTextChange = onEditTextChange,
                        placeholder = "name this playlist",
                        onDone = onCreate,
                        onCancel = onCancel,
                    )
                }
            }
            items(smart, key = { it.key }, span = { GridItemSpan(if (pinnedGrid) 1 else maxLineSpan) }) { sp ->
                if (pinnedGrid) {
                    SmartPlaylistTile(sp = sp, onOpen = { onOpenSmart(sp) }, loading = loading)
                } else {
                    SmartPlaylistRow(sp = sp, onOpen = { onOpenSmart(sp) }, loading = loading)
                }
            }

            items(
                pinnedPlaylists,
                key = { it.station.slug },
                span = { GridItemSpan(if (pinnedGrid) 1 else maxLineSpan) },
            ) { pl ->
                if (pl.station.slug == renamingSlug) {
                    InlineNameField(
                        text = editText,
                        onTextChange = onEditTextChange,
                        placeholder = "rename playlist",
                        onDone = { onRename(pl.station.slug, it) },
                        onCancel = onCancel,
                    )
                } else if (pinnedGrid) {
                    PlaylistTile(
                        pl = pl,
                        pinned = true,
                        onOpen = onOpen,
                        onEdit = { onBeginRename(pl.station.slug) },
                        onDelete = { onDelete(pl.station.slug) },
                        onAddSongs = { onAddSongs(pl.station.slug) },
                        onPin = { onPin(pl.station.slug, false) },
                        context = context,
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
                        context = context,
                    )
                }
            }
            if (playlists.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionLabel("playlists — ${playlists.size}") {
                        GridListToggle(gridMode = playlistsGrid, onToggle = onTogglePlaylistsGrid)
                    }
                }
            }
            items(
                playlists,
                key = { it.station.slug },
                span = { GridItemSpan(if (playlistsGrid) 1 else maxLineSpan) },
            ) { pl ->
                if (pl.station.slug == renamingSlug) {
                    InlineNameField(
                        text = editText,
                        onTextChange = onEditTextChange,
                        placeholder = "rename playlist",
                        onDone = { onRename(pl.station.slug, it) },
                        onCancel = onCancel,
                    )
                } else if (playlistsGrid) {
                    PlaylistTile(
                        pl = pl,
                        pinned = false,
                        onOpen = onOpen,
                        onEdit = { onBeginRename(pl.station.slug) },
                        onDelete = { onDelete(pl.station.slug) },
                        onAddSongs = { onAddSongs(pl.station.slug) },
                        onPin = { onPin(pl.station.slug, true) },
                        context = context,
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
                        context = context,
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(20.dp)) }
        }
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
    onAddProvider: (ProviderSpec) -> Unit,
    onRemoveProvider: (ProviderAccount) -> Unit,
) {
    val p = LocalPalette.current
    val connectedKeys = providers.map { it.providerKey }.toSet()
    // A Subsonic server holds one library and there is no point adding it
    // twice; SSH hosts are machines, and a nas and a seedbox are two of them.
    val available = ProviderCatalog.all.filter { it.multiple || it.key !in connectedKeys }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            SectionLabel("connected — ${providers.size}") { }
        }
        if (providers.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
                    Mono("nothing connected yet", CliampType.rowSecondary, p.inkFaint)
                }
            }
        } else {
            items(providers, key = { "prov:${it.id}" }) { acc ->
                ListRow(
                    onClick = { onOpenProvider(acc) },
                    verticalPadding = 11.dp,
                    leading = {
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(CliampShape.tiny))
                                .border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.tiny)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(CliampIcons.Server, null, Modifier.size(14.dp), tint = p.amber)
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
                                        "remove account",
                                        color = p.destructiveInk,
                                        action = { onRemoveProvider(acc) },
                                    ),
                                ),
                            )
                            Icon(CliampIcons.CaretRight, "open", Modifier.size(11.dp), tint = p.inkTertiary)
                        }
                    },
                ) {
                    Mono(acc.label.ifBlank { "provider" }, CliampType.rowPrimaryMedium, p.ink, maxLines = 1)
                    Mono(
                        ProviderCatalog.byKey(acc.providerKey)?.summary?.invoke(acc.values)
                            ?: acc.url,
                        CliampType.rowSecondary, p.inkTertiary, maxLines = 1,
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
                    Mono("every provider is connected", CliampType.rowSecondary, p.inkFaint)
                }
            }
        } else {
            items(available, key = { "add:${it.key}" }) { spec ->
                ListRow(
                    onClick = { onAddProvider(spec) },
                    verticalPadding = 11.dp,
                    leading = {
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(CliampShape.tiny))
                                .border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.tiny)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(CliampIcons.Server, null, Modifier.size(14.dp), tint = p.inkTertiary)
                        }
                    },
                    trailing = {
                        Icon(
                            CliampIcons.Plus, "add",
                            Modifier.size(11.dp).clip(RoundedCornerShape(CliampShape.tiny))
                                .background(p.accent.copy(alpha = 0.14f))
                                .padding(6.dp),
                            tint = p.accent,
                        )
                    },
                ) {
                    Mono(spec.name, CliampType.rowPrimaryMedium, p.ink, maxLines = 1)
                    Mono(spec.intro.firstOrNull().orEmpty(), CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
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
    context: android.content.Context,
) {
    val p = LocalPalette.current
    val byId = remember(songs) { songs.associate { it.id to it.name } }
    val cover = pl.station.cover
    var art by remember(pl.station.slug) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pl.station.slug, cover) {
        art = LocalArt.bitmapFor(cover, context.contentResolver)?.asImageBitmap()
    }
    ListRow(
        onClick = { onOpen(pl) },
        verticalPadding = 9.dp,
        leading = {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(CliampShape.small))
                    .then(if (art == null) Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.small)) else Modifier),
            ) {
                if (art != null) {
                    Image(art!!, pl.station.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    ArtPlate(modifier = Modifier.fillMaxSize(), radius = 5.dp, initial = pl.station.name)
                }
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Mono("${pl.songIds.size} songs", CliampType.meta, p.inkFaint)
                PlaylistMenu(pinned = pinned, onAddSongs = onAddSongs, onEdit = onEdit, onDelete = onDelete, onPin = onPin)
                Icon(CliampIcons.CaretRight, "open", Modifier.size(11.dp), tint = p.inkTertiary)
            }
        },
    ) {
        Mono(pl.station.name, CliampType.rowPrimaryMedium, p.ink, maxLines = 1)
        Mono(
            playlistPreview(pl.songIds, byId).ifBlank { "empty playlist" },
            CliampType.rowSecondary, p.inkTertiary, maxLines = 1,
        )
    }
}

/** A user playlist as a square tile: cover art, name, size, and the ⁝ menu.
 * The tile body opens the playlist; the ⁝ in the corner is the row's menu. */
@Composable
private fun PlaylistTile(
    pl: PlaylistStore.Playlist,
    pinned: Boolean,
    onOpen: (PlaylistStore.Playlist) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddSongs: () -> Unit,
    onPin: () -> Unit,
    context: android.content.Context,
) {
    val p = LocalPalette.current
    val cover = pl.station.cover
    var art by remember(pl.station.slug) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pl.station.slug, cover) {
        art = LocalArt.bitmapFor(cover, context.contentResolver)?.asImageBitmap()
    }
    Column(Modifier.fillMaxWidth().microPress { onOpen(pl) }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(CliampShape.medium))
                .background(p.panel)
                .border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.medium)),
        ) {
            if (art != null) {
                Image(art!!, pl.station.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
ArtPlate(modifier = Modifier.fillMaxSize(), initial = pl.station.name)
            }
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                PlaylistMenu(pinned = pinned, onAddSongs = onAddSongs, onEdit = onEdit, onDelete = onDelete, onPin = onPin)
            }
        }
        Spacer(Modifier.height(7.dp))
        Column(Modifier.padding(horizontal = 2.dp)) {
            Mono(pl.station.name, CliampType.rowPrimaryMedium, p.ink, maxLines = 2)
            Mono("${pl.songIds.size} songs", CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
        }
    }
}

/** A pinned, auto-populated smart playlist as a cover-collage tile. */
@Composable
private fun SmartPlaylistTile(
    sp: SmartPlaylist,
    onOpen: () -> Unit,
    loading: Boolean = false,
) {
    val p = LocalPalette.current
    val scanning = loading && sp.kind == SmartKind.LocalSongs && sp.stations.isEmpty()
    val kindIcon = smartKindIcon(sp.kind)
    val pulse = rememberInfiniteTransition(label = "scan")
    val shimmer by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "shimmer",
    )

    Column(Modifier.fillMaxWidth().microPress(onClick = onOpen)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(CliampShape.medium))
                .then(
                    if (scanning) Modifier.background(if (p.dark) p.ground else p.keyFace)
                    else Modifier.background(p.panel)
                )
                .border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.medium)),
        ) {
            if (scanning) {
                Box(Modifier.fillMaxSize().background(p.inkFaint.copy(alpha = 0.35f * shimmer)),
                    contentAlignment = Alignment.Center) {
                    Icon(kindIcon, sp.label, Modifier.size(22.dp), tint = p.inkFaint.copy(alpha = 0.7f * shimmer))
                }
            } else {
                SmartCollage(sp, Modifier.fillMaxSize())
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .size(26.dp)
                        .background(p.ground.copy(alpha = 0.85f), RoundedCornerShape(CliampShape.small))
                        .border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.small)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(kindIcon, sp.label, Modifier.size(12.dp), tint = p.accent)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Column(Modifier.padding(horizontal = 2.dp)) {
            Mono(sp.label, CliampType.rowPrimaryMedium, p.ink, maxLines = 2)
            if (scanning) {
                Box(Modifier.padding(top = 3.dp).width(110.dp).height(10.dp)
                    .clip(RoundedCornerShape(CliampShape.small))
                    .background(p.inkFaint.copy(alpha = 0.5f * shimmer)))
            } else {
                Mono(
                    if (sp.stations.isEmpty()) "nothing here yet" else "${sp.stations.size} items",
                    CliampType.rowSecondary, p.inkTertiary, maxLines = 1,
                )
            }
        }
    }
}

/** A pinned, auto-populated smart playlist as a list row (list layout). */
@Composable
private fun SmartPlaylistRow(
    sp: SmartPlaylist,
    onOpen: () -> Unit,
    loading: Boolean = false,
) {
    val p = LocalPalette.current
    val scanning = loading && sp.kind == SmartKind.LocalSongs && sp.stations.isEmpty()
    val kindIcon = smartKindIcon(sp.kind)
    val pulse = rememberInfiniteTransition(label = "scan")
    val shimmer by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "shimmer",
    )
    ListRow(
        onClick = onOpen,
        verticalPadding = 8.dp,
        leading = {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(CliampShape.small))) {
                if (scanning) {
                    Box(
                        Modifier.fillMaxSize().background(p.inkFaint.copy(alpha = 0.35f * shimmer)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(kindIcon, sp.label, Modifier.size(16.dp), tint = p.inkFaint.copy(alpha = 0.7f * shimmer))
                    }
                } else {
                    SmartCollage(sp, Modifier.fillMaxSize())
                }
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (scanning) {
                    Box(Modifier.width(52.dp).height(12.dp).clip(RoundedCornerShape(CliampShape.small))
                        .background(p.inkFaint.copy(alpha = 0.35f * shimmer)))
                } else {
                    Mono(
                        if (sp.stations.isEmpty()) "empty" else "${sp.stations.size} items",
                        CliampType.meta, p.inkFaint,
                    )
                }
                Icon(CliampIcons.CaretRight, "open", Modifier.size(11.dp), tint = p.inkTertiary)
            }
        },
    ) {
        Mono(sp.label, CliampType.rowPrimaryMedium, p.ink, maxLines = 1)
        if (!scanning && sp.stations.isEmpty()) {
            Mono("nothing here yet", CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
        }
    }
}

/** The smart playlist's identifying glyph. */
private fun smartKindIcon(kind: SmartKind): ImageVector = when (kind) {
    SmartKind.LocalSongs -> CliampIcons.MusicNote
    SmartKind.Favorites -> CliampIcons.Star
    SmartKind.RecentlyPlayed -> CliampIcons.Clock
}

/** The 2x2 member-cover mosaic shared by the smart-playlist tile and row. */
@Composable
private fun SmartCollage(sp: SmartPlaylist, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val kindIcon = smartKindIcon(sp.kind)
    val members = remember(sp.stations) { sp.stations.take(4) }
    var arts by remember(members) { mutableStateOf<List<ImageBitmap?>>(members.map { null }) }
    LaunchedEffect(members) {
        arts = members.map { s ->
            val bmp = when (s.source) {
                StationSource.Local ->
                    LocalArt.bitmapForSmall(s.cover, context.contentResolver)
                        ?: StationArtSource.bitmapForSmall(s)
                else -> StationArtSource.bitmapForSmall(s)
            }
            bmp?.asImageBitmap()
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            CoverCell(arts.getOrNull(0), members.getOrNull(0)?.name, kindIcon, sp.label, Modifier.weight(1f).fillMaxHeight())
            CoverCell(arts.getOrNull(1), members.getOrNull(1)?.name, kindIcon, sp.label, Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            CoverCell(arts.getOrNull(2), members.getOrNull(2)?.name, kindIcon, sp.label, Modifier.weight(1f).fillMaxHeight())
            CoverCell(arts.getOrNull(3), members.getOrNull(3)?.name, kindIcon, sp.label, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/**
 * One quarter of a smart-playlist collage: member cover art, a plate with the
 * member's initial when the song has no cover, and for a quarter with no member
 * at all the smart playlist's own glyph - so an empty or half-empty playlist
 * still reads as something rather than four bare tiles.
 */
@Composable
private fun CoverCell(
    art: ImageBitmap?,
    initial: String?,
    kindIcon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Box(modifier.background(p.artB)) {
        if (art != null) {
            Image(art, contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (initial != null) {
            ArtPlate(modifier = Modifier.fillMaxSize(), initial = initial)
        } else {
            ArtPlate(modifier = Modifier.fillMaxSize()) {
                Icon(kindIcon, null, Modifier.align(Alignment.Center).size(18.dp), tint = p.accent.copy(alpha = 0.22f))
            }
        }
    }
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
            Modifier.size(36.dp).clip(RoundedCornerShape(CliampShape.small)).microPress { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                CliampIcons.More, "menu",
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
                    Modifier.width(170.dp).clip(RoundedCornerShape(CliampShape.small))
                        .background(p.ground).border(1.dp, p.hairlineRegion, RoundedCornerShape(CliampShape.small)),
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
        Mono(label, CliampType.chip, color)
    }
}

/** A monospace, palette-styled input row for naming playlists. The keyboard
 * The platform IME handles entry; the field takes focus on appearing. */
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
            .clip(RoundedCornerShape(CliampShape.small)).border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.small))
            .background(p.panel).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CliampTextField(
            value = text,
            onValueChange = { onTextChange(it.take(48)) },
            modifier = Modifier.weight(1f),
            placeholder = placeholder,
            textStyle = CliampType.rowPrimary,
            onAction = { onDone(text) },
            autoFocus = true,
        )
        Mono("SAVE", CliampType.tabLabel, p.accent,
            Modifier.clip(RoundedCornerShape(CliampShape.tiny)).background(p.accent.copy(alpha = 0.14f))
                .microPress { onDone(text) }.padding(horizontal = 9.dp, vertical = 7.dp))
        Mono("CANCEL", CliampType.tabLabel, p.inkTertiary,
            Modifier.clip(RoundedCornerShape(CliampShape.tiny)).border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.tiny))
                .microPress(onClick = onCancel).padding(horizontal = 9.dp, vertical = 7.dp))
    }
}

/**
 * Preview line under a user playlist row. Uses an id→name map (O(1) per id)
 * instead of a linear scan, and caps the number of names shown.
 */
private fun playlistPreview(songIds: List<String>, byId: Map<String, String>): String {
    if (songIds.isEmpty()) return ""
    val head = 4
    val names = songIds.take(head).joinToString(" · ") { byId[it] ?: "…" }
    if (songIds.size <= head) return names
    return "$names · +${songIds.size - head} more"
}

@Composable
private fun PlaylistDetailShown(
    listState: LazyListState,
    playlist: PlaylistStore.Playlist,
    songIds: List<String>,
    playlists: PlaylistStore,
    localSongs: List<Station>,
    radioStations: List<Station>,
    podcasts: PodcastRepository,
    subscribedShows: List<PodcastShow>,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onToggle: (Station, Boolean) -> Unit,
    adding: Boolean,
    doneAdding: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val prefs = (context.applicationContext as CliampApp).prefs
    val sort by prefs.playlistSort(playlist.station.slug)
        .collectAsState(initial = prefs.playlistSortValue(playlist.station.slug))

    // Members can be any source now, so they resolve against the live local
    // library plus the persisted snapshot stations (radio/podcast members).
    var members by remember(songIds) { mutableStateOf<List<Station>>(emptyList()) }
    LaunchedEffect(songIds, localSongs) {
        members = playlists.resolveMembers(songIds, localSongs)
    }

    if (adding) {
        AddSongsPicker(
            selected = songIds.toSet(),
            playlistName = playlist.station.name,
            localSongs = localSongs,
            radioStations = radioStations,
            podcasts = podcasts,
            subscribedShows = subscribedShows,
            onToggle = onToggle,
            doneAdding = doneAdding,
        )
        return
    }

    val visible = remember(members, sort) { sortedStations(members, sort) }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        if (members.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Mono("empty — tap add", CliampType.rowSecondary, p.inkFaint)
                }
            }
        } else {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = Gutter, end = Gutter, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    PlaylistSort.entries.forEach { t ->
                        Chip(t.label, sort == t, onClick = {
                            prefs.setPlaylistSort(playlist.station.slug, t)
                        })
                    }
                }
            }
            item { SectionLabel("songs — ${members.size}") }
            items(visible, key = { it.id }) { s ->
                ListRow(
                    rail = current?.url == s.url,
                    onClick = { onPlay(s, visible) },
                    verticalPadding = 9.dp,
                    leading = {
                        SongCover(s = s, current = current, playing = playing)
                    },
                    trailing = {
                        Mono("DROP", CliampType.tabLabel, p.destructiveInk,
                            Modifier.clip(RoundedCornerShape(CliampShape.tiny)).microPress { onToggle(s, false) }
                                .padding(horizontal = 8.dp, vertical = 6.dp))
                    },
                ) {
                    Mono(s.name, CliampType.rowPrimary, if (current?.url == s.url) p.accent else p.ink, maxLines = 1)
                    Mono(
                        s.artistAlbum.ifBlank {
                            when {
                                s.source == StationSource.Local -> durationLabel(s.durationMs)
                                s.source == StationSource.Podcast -> "podcast"
                                else -> s.meta
                            }
                        },
                        CliampType.rowSecondary, p.inkTertiary, maxLines = 1,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

private enum class AddTab(val label: String) { Local("local"), Stations("stations"), Podcasts("podcasts") }

/**
 * The "add songs" picker: local songs, radio stations and podcast episodes in
 * their own sub-tabs, with one shared selection carried across all of them.
 * The header keeps the done control and a live count always in view.
 */
@Composable
private fun AddSongsPicker(
    selected: Set<String>,
    playlistName: String,
    localSongs: List<Station>,
    radioStations: List<Station>,
    podcasts: PodcastRepository,
    subscribedShows: List<PodcastShow>,
    onToggle: (Station, Boolean) -> Unit,
    doneAdding: () -> Unit,
) {
    val p = LocalPalette.current
    var tab by remember { mutableStateOf(AddTab.Local) }
    var openShow by remember { mutableStateOf<PodcastShow?>(null) }
    var picked by remember { mutableStateOf(selected) }
    val showState by podcasts.show.collectAsState(initial = ShowState())

    fun toggle(s: Station) {
        val add = s.id !in picked
        picked = if (add) picked + s.id else picked - s.id
        onToggle(s, add)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Mono(playlistName, CliampType.chip, p.accent, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Mono("${picked.size} selected", CliampType.meta, p.inkTertiary)
            Chip("done", selected = false, onClick = doneAdding)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = Gutter, end = Gutter, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            AddTab.entries.forEach { t -> Chip(t.label, tab == t, onClick = { tab = t }) }
        }
        HairlineDivider(region = true)

        when (tab) {
            AddTab.Local -> GroupList(
                items = localSongs,
                picked = picked,
                subtitle = { s ->
                    s.artistAlbum.ifBlank { durationLabel(s.durationMs) }
                },
                onToggle = ::toggle,
                empty = "no local songs yet",
            )
            AddTab.Stations -> GroupList(
                items = radioStations,
                picked = picked,
                subtitle = { s -> s.meta },
                onToggle = ::toggle,
                empty = "no stations to add",
            )
            AddTab.Podcasts -> PodcastGroups(
                podcasts = podcasts,
                shows = subscribedShows,
                openShow = openShow,
                onOpenShow = { show ->
                    openShow = show
                    podcasts.openShow(show)
                },
                onBackToShows = { openShow = null },
                showState = showState,
                picked = picked,
                onToggle = ::toggle,
            )
        }
    }
}

@Composable
private fun GroupList(
    items: List<Station>,
    picked: Set<String>,
    subtitle: (Station) -> String,
    onToggle: (Station) -> Unit,
    empty: String,
) {
    val p = LocalPalette.current
    LazyColumn(Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Mono(empty, CliampType.rowSecondary, p.inkFaint)
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
                            Modifier.size(24.dp).clip(RoundedCornerShape(CliampShape.tiny))
                                .then(if (inPl) Modifier.background(p.accent)
                                      else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.tiny))),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (inPl) Icon(CliampIcons.Check, null, Modifier.size(10.dp), tint = p.onAccent)
                        }
                    },
                ) {
                    Mono(s.name, CliampType.rowPrimary, p.ink, maxLines = 1)
                    Mono(subtitle(s), CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun PodcastGroups(
    podcasts: PodcastRepository,
    shows: List<PodcastShow>,
    openShow: PodcastShow?,
    onOpenShow: (PodcastShow) -> Unit,
    onBackToShows: () -> Unit,
    showState: ShowState,
    picked: Set<String>,
    onToggle: (Station) -> Unit,
) {
    val p = LocalPalette.current
    val show = openShow
    if (show != null) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = Gutter, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Chip("‹ shows", selected = false, onClick = onBackToShows)
                    Spacer(Modifier.width(2.dp))
                    Mono(show.title, CliampType.chip, p.ink, maxLines = 1)
                }
            }
            if (showState.loading) {
                item { Mono("loading episodes…", CliampType.rowSecondary, p.inkFaint, Modifier.padding(horizontal = Gutter, vertical = 12.dp)) }
            } else {
                val eps = showState.episodes.filter { it.isFull }
                items(eps, key = { "pod:${show.id}:${it.guid}" }) { e ->
                    val s = e.toStation(show)
                    val inPl = s.id in picked
                    ListRow(
                        onClick = { onToggle(s) },
                        verticalPadding = 8.dp,
                        leading = {
                            Box(
                                Modifier.size(24.dp).clip(RoundedCornerShape(CliampShape.tiny))
                                    .then(if (inPl) Modifier.background(p.accent)
                                          else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.tiny))),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (inPl) Icon(CliampIcons.Check, null, Modifier.size(10.dp), tint = p.onAccent)
                            }
                        },
                    ) {
                        Mono(s.name, CliampType.rowPrimary, p.ink, maxLines = 1)
                        Mono(durationLabel(s.durationMs).ifBlank { "episode" }, CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
                    }
                }
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        if (shows.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Mono("no subscribed podcasts yet", CliampType.rowSecondary, p.inkFaint)
                }
            }
        } else {
            items(shows, key = { it.feedUrl }) { show ->
                ListRow(
                    onClick = { onOpenShow(show) },
                    verticalPadding = 9.dp,
                    trailing = { Icon(CliampIcons.CaretRight, "open", Modifier.size(11.dp), tint = p.inkTertiary) },
                ) {
                    Mono(show.title, CliampType.rowPrimary, p.ink, maxLines = 1)
                    Mono(show.meta, CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
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
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onToggleFavorite: (Station) -> Unit,
    favorites: Set<String>,
    loading: Boolean = false,
    favScope: FavScope = FavScope.All,
    onFavScopeChange: (FavScope) -> Unit = {},
    onInfo: (Station) -> Unit = {},
    onRemove: (Station) -> Unit = {},
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val prefs = (context.applicationContext as CliampApp).prefs
    // Only the local-songs smart list sorts; favourites and recent have their
    // own fixed orders (recent is already time-sorted).
    val local = pl.kind == SmartKind.LocalSongs
    // Favourites mix local songs, radio stations and podcasts, so they get
    // their own type sub-tabs: all / local / stations / podcasts.
    val isFav = pl.kind == SmartKind.Favorites
    val sort by prefs.playlistSort("local-songs")
        .collectAsState(initial = prefs.playlistSortValue("local-songs"))
    val members = pl.stations
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
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        if (isFav) {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = Gutter, end = Gutter, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    FavScope.entries.forEach { f ->
                        Chip(f.label, favScope == f, onClick = { onFavScopeChange(f) })
                    }
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Mono(
                        when {
                            pl.kind == SmartKind.Favorites && favScope == FavScope.Local -> "no local favourites yet"
                            pl.kind == SmartKind.Favorites && favScope == FavScope.Stations -> "no station favourites yet"
                            pl.kind == SmartKind.Favorites && favScope == FavScope.Pods -> "no podcast favourites yet"
                            pl.kind == SmartKind.LocalSongs ->
                                if (loading) "scanning for songs…" else "no songs on the phone yet"
                            pl.kind == SmartKind.Favorites -> "no favourites yet"
                            else -> "nothing played recently"
                        },
                        CliampType.rowSecondary, p.inkFaint,
                    )
                }
            }
        } else {
            if (local) {
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(start = Gutter, end = Gutter, top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        PlaylistSort.entries.forEach { t ->
                            Chip(t.label, sort == t, onClick = {
                                prefs.setPlaylistSort("local-songs", t)
                            })
                        }
                    }
                }
            }
            item { SectionLabel("${pl.label} — ${visible.size}") }
            items(visible, key = { it.url }, contentType = { "local-song" }) { s ->
                ListRow(
                    rail = current?.url == s.url,
                    onClick = { onPlay(s, visible) },
                    verticalPadding = 9.dp,
                    leading = {
                        SongCover(s = s, current = current, playing = playing)
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // ⋮ menu sits immediately to the left of the star,
                            // only on the local-songs list: favourites and
                            // recently-played are read-only views.
                            if (local) {
                                OverflowMenu(
                                    trigger = { open -> OverflowButton(open, size = 16) },
                                    items = listOf(
                                        OverflowItem("info", color = p.ink, action = { onInfo(s) }),
                                        OverflowItem("remove", color = p.destructiveInk, action = { onRemove(s) }),
                                    ),
                                )
                            }
                            Icon(
                                if (s.url in favorites) CliampIcons.StarFilled else CliampIcons.Star,
                                "favourite",
                                Modifier.size(15.dp).microPress { onToggleFavorite(s) },
                                tint = if (s.url in favorites) p.accent else p.inkFaint,
                            )
                        }
                    },
                ) {
                    Mono(s.name, CliampType.rowPrimary, if (current?.url == s.url) p.accent else p.ink, maxLines = 1)
                    Mono(
                        when (s.source) {
                            StationSource.Podcast -> s.artist.ifBlank { s.meta.ifBlank { "podcast" } }
                            StationSource.Local -> s.artistAlbum.ifBlank { s.meta }
                            else -> buildList {
                                s.meta.takeIf { it.isNotBlank() }?.let { add(it) }
                                s.tagList.take(2).forEach { add(it) }
                            }.joinToString(" · ")
                        },
                        CliampType.rowSecondary, p.inkTertiary, maxLines = 1,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

/**
 * A song-row's leading thumbnail: real cover art when the file has it, with a
 * small play/pause badge overlaid when it is the current track.
 */
@Composable
private fun SongCover(s: Station, current: Station?, playing: Boolean) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var art by remember(s.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(s.id) {
        art = (LocalArt.bitmapForSmall(s.cover, context.contentResolver)
            ?: StationArtSource.bitmapForSmall(s))
            ?.asImageBitmap()
    }
    val active = current?.url == s.url
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(CliampShape.small))
            .then(
                if (art != null) Modifier.background(p.panel)
                else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.small))
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art!!, s.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(CliampIcons.MusicNote, null, Modifier.size(15.dp), tint = p.inkTertiary)
        }
        if (active) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(18.dp)
                    .clip(RoundedCornerShape(CliampShape.tiny))
                    .background(p.accent.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) CliampIcons.Pause else CliampIcons.PlayRow,
                    null,
                    Modifier.size(9.dp),
                    tint = p.onAccent,
                )
            }
        }
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Mono("Info", CliampType.screenTitle, p.ink, maxLines = 1)
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    BackChip(onClick = onDismiss)
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
                            .shadow(26.dp, RoundedCornerShape(CliampShape.large))
                            .clip(RoundedCornerShape(CliampShape.large)).background(p.artB),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (art != null) {
                            Image(art!!, s.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(CliampIcons.MusicNote, null, Modifier.size(40.dp), tint = p.inkTertiary)
                        }
                        if (favorite) {
                            Mono(
                                "♥ favourite", CliampType.chip, p.onAccent,
                                Modifier.align(Alignment.TopStart).padding(8.dp)
                                    .clip(RoundedCornerShape(CliampShape.tiny)).background(p.accent.copy(alpha = 0.92f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                Column(Modifier.padding(horizontal = Gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Mono(s.name, CliampType.trackTitleCompact, p.ink, maxLines = 2)
                    Mono(s.artist.ifBlank { "unknown artist" }, CliampType.rowSecondary, p.inkTertiary)
                    if (s.album.isNotBlank()) Mono(s.album, CliampType.body, p.inkTertiary)
                }

                Column(
                    Modifier.fillMaxWidth().padding(Gutter),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    SongInfoRow("duration", durationLabel(s.durationMs))
                    SongInfoRow("added", added)
                    SongInfoRow("size", sizeLabel)
                    SongInfoRow("format", File(path).extension.uppercase().ifBlank { "—" })
                    SongInfoRow("location", File(path).parent.orEmpty())
                }

                Spacer(Modifier.height(20.dp))

                // Actions: favourite toggle and destructive remove, side by side.
                Row(Modifier.fillMaxWidth().padding(Gutter)) {
                    Row(
                        Modifier.padding(end = 4.dp).weight(1f).clip(RoundedCornerShape(CliampShape.small)).background(p.panel)
                            .microPress { onToggleFavorite(s) }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (favorite) CliampIcons.StarFilled else CliampIcons.Star,
                            "favourite",
                            Modifier.size(15.dp).padding(end = 6.dp),
                            tint = if (favorite) p.accent else p.inkTertiary,
                        )
                        Mono(if (favorite) "favourited" else "favourite", CliampType.chip, p.ink)
                    }
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(CliampShape.small)).background(p.panel)
                            .microPress { onRemove() }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Mono("remove from device", CliampType.chip, p.destructiveInk)
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
            Mono(label, CliampType.meta, p.inkFaint, Modifier.width(80.dp))
            Mono(value, CliampType.rowSecondary, p.ink, maxLines = 2)
        }
        HairlineDivider()
    }
}
