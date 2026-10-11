package stream.kleeamp.mobile.library

import android.app.Activity
import android.content.Intent
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
import stream.kleeamp.mobile.art.SeedPlate
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.radio.RadioRepository
import stream.kleeamp.mobile.podcasts.ShowState
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.radio.DirectoryState
import stream.kleeamp.mobile.podcasts.EpisodeProgress
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.podcasts.toStation
import stream.kleeamp.mobile.podcasts.downloadSizeLabel
import stream.kleeamp.mobile.servers.ProviderAccount
import stream.kleeamp.mobile.servers.ProviderCatalog
import stream.kleeamp.mobile.servers.displayName
import stream.kleeamp.mobile.servers.ProviderSpec
import stream.kleeamp.mobile.servers.SftpLibrary
import stream.kleeamp.mobile.chrome.rememberStationThumbnail
import stream.kleeamp.mobile.chrome.rememberArt
import stream.kleeamp.mobile.chrome.ArtKind
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.ChipDropdown
import stream.kleeamp.mobile.chrome.ChipOption
import stream.kleeamp.mobile.chrome.CollectionActions
import stream.kleeamp.mobile.chrome.CollectionHeader
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
import stream.kleeamp.mobile.chrome.ContextMenuSheet
import stream.kleeamp.mobile.chrome.DestructiveAction
import stream.kleeamp.mobile.chrome.MenuKind
import stream.kleeamp.mobile.chrome.MenuSubject
import stream.kleeamp.mobile.chrome.StationMenuArt
import stream.kleeamp.mobile.chrome.menuActions
import stream.kleeamp.mobile.chrome.ScreenHeader
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.chrome.scrollToTop
import stream.kleeamp.mobile.chrome.ArtGlow
import stream.kleeamp.mobile.chrome.MainLayout
import stream.kleeamp.mobile.chrome.NoticeBus
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono
import stream.kleeamp.mobile.prefs.PlaylistSort
import stream.kleeamp.mobile.prefs.sortedStations

/** Playlist maintenance: dedupe, missing-file purge and position bookmark. */
@Composable
private fun PlaylistToolsMenu(
    state: PlaylistToolsState,
    onDedupe: () -> Unit,
    onPurgeMissing: () -> Unit,
    onBookmarkCurrent: () -> Unit,
    onGoBookmark: () -> Unit,
    onClearBookmark: () -> Unit,
) {
    val p = LocalPalette.current
    val items = buildList {
        if (state.dupCount > 0) {
            add(OverflowItem("remove ${state.dupCount} duplicates", onDedupe, p.ink))
        }
        if (state.missingCount > 0) {
            add(OverflowItem("remove ${state.missingCount} missing", onPurgeMissing, p.ink))
        }
        if (state.canBookmark) add(OverflowItem("bookmark current", onBookmarkCurrent, p.ink))
        if (state.bookmarkIndex != null) {
            add(OverflowItem("go to bookmark", onGoBookmark, p.accent))
            add(OverflowItem("clear bookmark", onClearBookmark, p.ink))
        }
    }
    if (items.isEmpty()) return
    OverflowMenu(
        trigger = { open -> OverflowButton(open, size = 16) },
        items = items,
    )
}

/** Inputs for [PlaylistToolsMenu], read off the detail state. */
private data class PlaylistToolsState(
    val dupCount: Int,
    val missingCount: Int,
    val bookmarkIndex: Int?,
    val canBookmark: Boolean,
)

/** One user playlist as a navigation pane, with add-songs and cover editing. */

@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
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
    onInfo: ((Station) -> Unit)? = null,
    onOpenArtist: (String) -> Unit = {},
    /** True when opened from a playlist row's add menu: lands in the song picker. */
    startAdding: Boolean = false,
    shuffled: Boolean = false,
    onToggleShuffle: () -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var adding by rememberSaveable(slug) { mutableStateOf(startAdding) }
    var query by rememberSaveable(slug) { mutableStateOf("") }
    // The row menu's subject: set by the ⋮ trigger, cleared on dismiss.
    var menuFor by remember { mutableStateOf<Station?>(null) }
    val ui by vm.state.collectAsState()
    // Back closes the picker first; writes already landed per tap, so there
    // is nothing to save — a second back leaves the page.
    BackHandler(enabled = adding) { adding = false }
    val songs = ui.localSongs
    val radioStations = ui.radioStations
    val subscriptions = ui.subscribedShows
    val pl = ui.playlist
    val coverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Keep read access past this process: a GetContent grant dies with
        // it, which is what used to blank the cover on the next launch.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        vm.onEvent(PlaylistDetailViewModel.Event.SetCover(uri.toString()))
    }
    // Exported M3U text waiting for the save picker: generated on tap,
    // written when the user picks a destination.
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        val pending = pendingExport
        pendingExport = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(pending.second.toByteArray())
            } ?: error("no stream")
        }.onSuccess { NoticeBus.show("saved ${pending.first}.m3u") }
            .onFailure { NoticeBus.show("export failed", error = true) }
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
                    Chip("set cover", selected = false, onClick = { coverLauncher.launch(arrayOf("image/*")) })
                    if (ui.members.isNotEmpty()) {
                        Chip(
                            "export",
                            selected = false,
                            onClick = {
                                scope.launch {
                                    val text = vm.exportM3u() ?: return@launch
                                    val name = pl.station.name.ifBlank { "playlist" }
                                        .replace('/', '_')
                                    pendingExport = name to text
                                    exportLauncher.launch("$name.m3u")
                                }
                            },
                        )
                    }
                    PlaylistToolsMenu(
                        state = PlaylistToolsState(
                            dupCount = ui.dupCount,
                            missingCount = ui.missingCount,
                            bookmarkIndex = ui.bookmarkIndex,
                            canBookmark = current != null &&
                                ui.members.indexOfFirst { it.url == current.url } >= 0,
                        ),
                        onDedupe = { vm.onEvent(PlaylistDetailViewModel.Event.Dedupe) },
                        onPurgeMissing = { vm.onEvent(PlaylistDetailViewModel.Event.PurgeMissing) },
                        onBookmarkCurrent = {
                            val at = ui.members.indexOfFirst { it.url == current?.url }
                            if (at >= 0) vm.onEvent(PlaylistDetailViewModel.Event.Bookmark(at))
                        },
                        onGoBookmark = {
                            ui.bookmarkIndex?.let { idx ->
                                ui.members.getOrNull(idx)?.let { onPlay(it, ui.members) }
                            }
                        },
                        onClearBookmark = {
                            vm.onEvent(PlaylistDetailViewModel.Event.Bookmark(null))
                        },
                    )
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
                        browse = ui.browse,
                        onBrowseChange = { vm.onEvent(PlaylistDetailViewModel.Event.SetBrowse(it)) },
                        onOpenArtist = onOpenArtist,
                        localSongs = songs,
                        radioStations = radioStations,
                        subscribedShows = subscriptions,
                        showState = ui.showState,
                        onOpenShow = { vm.onEvent(PlaylistDetailViewModel.Event.OpenShow(it)) },
                        current = current,
                        playing = playing,
                        onPlay = onPlay,
                        onToggle = { vm.onEvent(PlaylistDetailViewModel.Event.ToggleMember(it)) },
                        onOpenMenu = { menuFor = it },
                        adding = adding,
                        onBeginAdd = { adding = true },
                        shuffled = shuffled,
                        onToggleShuffle = onToggleShuffle,
                    )
                }

                // The row menu as a bottom sheet: favourites, playlists,
                // queue, info where it resolves, and drop from the list.
                menuFor?.let { s ->
                    val fav = ui.favorites.any { it.url == s.url }
                    val info = if (s.source != StationSource.Custom || fav) onInfo else null
                    ContextMenuSheet(
                        title = s.name,
                        subtitle = s.artistAlbum.ifBlank {
                            when {
                                s.source == StationSource.Local -> durationLabel(s.durationMs)
                                s.source == StationSource.Podcast -> "podcast"
                                else -> s.meta
                            }
                        },
                        art = { StationMenuArt(s) },
                        actions = menuActions(
                            MenuSubject(
                                kind = MenuKind.STATION,
                                favorite = fav,
                                infoAvailable = info != null,
                                destructive = DestructiveAction(
                                    "drop",
                                    "remove from this playlist",
                                    { vm.onEvent(PlaylistDetailViewModel.Event.RemoveMember(s)) },
                                ),
                                onQueue = { onAddToQueue(s) },
                                onToggleFavorite = {
                                    vm.onEvent(PlaylistDetailViewModel.Event.ToggleFavorite(s))
                                },
                                onAddToPlaylist = { onAddToPlaylist(s) },
                                onInfo = info?.let { show -> { show(s) } },
                            ),
                        ),
                        onDismiss = { menuFor = null },
                    )
                }
            }
        }
    }
}

@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
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
    browse: Boolean = false,
    onBrowseChange: (Boolean) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
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
    onBeginAdd: () -> Unit = {},
    onOpenMenu: (Station) -> Unit = {},
    shuffled: Boolean = false,
    onToggleShuffle: () -> Unit = {},
) {
    val p = LocalPalette.current

    // Browse regroups the members under album rows in one flat scroll —
    // no pages, no back handling. The sorts show a local override until
    // the stored sort round-trips back, so the whole row answers on
    // the tap.
    var sortOverride by remember(playlist.station.slug) { mutableStateOf<PlaylistSort?>(null) }
    LaunchedEffect(sort) { sortOverride = null }
    val shownSort = sortOverride ?: sort
    // Browse lists artists only — one row per artist, tap opens their
    // page — cheapest last so the flat list never waits for it.
    val artists = remember(visible, browse) {
        if (!browse) emptyList() else artistsOf(visible)
    }

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
        item {
            val cover = rememberArt(station = playlist.station, kind = ArtKind.Full)
            CollectionHeader(
                title = playlist.station.name,
                meta = "${members.size} songs",
                actions = {
                    if (visible.isNotEmpty()) {
                        CollectionActions(
                            shuffled = shuffled,
                            onPlayAll = { visible.firstOrNull()?.let { onPlay(it, visible) } },
                            onShufflePlay = {
                                if (!shuffled) onToggleShuffle()
                                visible.randomOrNull()?.let { onPlay(it, visible) }
                            },
                        )
                    }
                },
                art = {
                    if (cover != null) {
                        Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        SeedPlate(
                            key = playlist.station.slug,
                            name = playlist.station.name,
                            modifier = Modifier.fillMaxSize(),
                            radius = KleeampShape.small,
                        )
                    }
                },
            )
        }
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
                    // Lit while browsing, but never untapped: a re-tap
                    // is a no-op. Only a sort tap switches back.
                    Chip("browse", browse, onClick = {
                        if (!browse) {
                            onBrowseChange(true)
                            onQuery("")
                        }
                    })
                    // Sorts share one active state with browse: a tap applies
                    // the sort to the flat list.
                    PlaylistSort.entries.forEach { t ->
                        Chip(t.label, shownSort == t && !browse, onClick = {
                            onBrowseChange(false)
                            sortOverride = t
                            onSortChange(t)
                        })
                    }
                }
            }
            item { FilterRow(value = query, onValue = onQuery) }
            if (browse && visible.isNotEmpty()) {
                items(artists, key = { it.name }) { group ->
                    ListRow(
                        onClick = { onOpenArtist(group.name) },
                        verticalPadding = 9.dp,
                        leading = {
                            QuadCollage(group.songs, Modifier.size(40.dp))
                        },
                        trailing = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Mono("${group.songs.size}", KleeampType.meta, p.inkFaint)
                                Icon(KleeampIcons.CaretRight, "open", Modifier.size(9.dp), tint = p.inkFaint)
                            }
                        },
                    ) {
                        Mono(group.name, KleeampType.rowPrimary, p.ink, maxLines = 1)
                    }
                }
            } else {
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
                PlaylistMemberRow(
                    s = s,
                    current = current,
                    playing = playing,
                    queue = visible,
                    onPlay = onPlay,
                    onOpenMenu = onOpenMenu,
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
internal fun AddSongsButton(onClick: () -> Unit) {
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
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
internal fun AddSongsPicker(
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
