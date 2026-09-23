package stream.kleeamp.mobile.library

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
import stream.kleeamp.mobile.art.LocalArt
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.library.LocalLibrary
import stream.kleeamp.mobile.library.PlaylistStore
import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.radio.RadioRepository
import stream.kleeamp.mobile.podcasts.ShowState
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.radio.DirectoryState
import stream.kleeamp.mobile.podcasts.EpisodeProgress
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.podcasts.toStation
import stream.kleeamp.mobile.library.durationLabel
import stream.kleeamp.mobile.podcasts.downloadSizeLabel
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
import stream.kleeamp.mobile.prefs.PlaylistSort
import stream.kleeamp.mobile.prefs.sortedStations

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


@Composable
fun LibraryScreen(
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
