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
import stream.kleeamp.mobile.art.LocalArt
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.data.LocalLibrary
import stream.kleeamp.mobile.data.PlaylistStore
import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.radio.RadioRepository
import stream.kleeamp.mobile.podcasts.ShowState
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.radio.DirectoryState
import stream.kleeamp.mobile.podcasts.EpisodeProgress
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.podcasts.toStation
import stream.kleeamp.mobile.data.durationLabel
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

/** One provider album row for the providers drill: name, song count, year. */
private data class ProviderAlbumRow(val name: String, val count: Int, val year: Int)

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


/**
 * The providers entry in the pinned list: one row standing in for every
 * connected account's songs. The count reads accounts, not songs - songs
 * load when the row opens, so the row stays instant like the smart rows
 * around it.
 */

@Composable
internal fun ProvidersRow(count: Int, onOpen: () -> Unit) {
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
