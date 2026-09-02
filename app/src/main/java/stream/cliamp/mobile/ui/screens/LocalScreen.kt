package stream.cliamp.mobile.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.LocalArt
import stream.cliamp.mobile.data.LocalLibrary
import stream.cliamp.mobile.data.PlaylistStore
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.durationLabel
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.CliampIcons
import androidx.compose.ui.text.input.ImeAction
import stream.cliamp.mobile.data.provider.ProviderAccount
import stream.cliamp.mobile.data.provider.ProviderCatalog
import stream.cliamp.mobile.data.provider.ProviderSpec
import stream.cliamp.mobile.ui.components.CliampTextField
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.IconLabelButton
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.ScreenHeader
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.components.StripedArt
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

/** The pinned, auto-populated smart playlists on the library tab. */
private enum class SmartKind(val label: String) {
    LocalSongs("local songs"),
    Favorites("favorites"),
    RecentlyPlayed("recently played");

    val key: String get() = "smart:$name"
}

/** A derived smart playlist: a label plus its current member stations. */
private class SmartPlaylist(val kind: SmartKind, val stations: List<Station>) {
    val label: String get() = kind.label
    val key: String get() = kind.key
}

/**
 * The LIB tab's local half. Reads the phone's audio library via [LocalLibrary]
 * and lets the user play any song (through the normal Station pipeline, so
 * queue/prev/next/artwork all just work) and build playlists with covers.
 */
@Composable
fun LocalScreen(
    localLibrary: LocalLibrary,
    playlists: PlaylistStore,
    current: Station?,
    playing: Boolean,
    favorites: List<Station>,
    recent: List<Station>,
    onPlay: (Station, List<Station>) -> Unit,
    onToggleFavorite: (Station) -> Unit,
    onAddToQueue: (Station) -> Unit,
    onOpenPlayer: () -> Unit,
    providers: List<ProviderAccount> = emptyList(),
    showProviders: Boolean = false,
    onShowProviders: (Boolean) -> Unit = {},
    onOpenProvider: (ProviderAccount) -> Unit = {},
    onAddProvider: (ProviderSpec) -> Unit = {},
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var openSlug by remember { mutableStateOf<String?>(null) }
    var openSmart by remember { mutableStateOf<SmartKind?>(null) }
    var addingTo by remember { mutableStateOf<String?>(null) }
    var creatingName by remember { mutableStateOf(false) }
    var renamingSlug by remember { mutableStateOf<String?>(null) }
    var nameText by remember { mutableStateOf("") }

    val songs by localLibrary.songs.collectAsState()
    val loading by localLibrary.loading.collectAsState()
    val libError by localLibrary.error.collectAsState()
    val allPlaylists by playlists.playlists.collectAsState(initial = emptyList())
    val pinnedSlugs by playlists.pinnedSlugs.collectAsState(initial = emptySet())
    val filteredPlaylists = filterPlaylists(allPlaylists, query)
    val pinnedPlaylists = filteredPlaylists.filter { it.station.slug in pinnedSlugs }
    val unpinnedPlaylists = filteredPlaylists.filterNot { it.station.slug in pinnedSlugs }

    val audioPerm = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    var haveAudio by remember { mutableStateOf(checkAudio(context, audioPerm)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> haveAudio = ok; if (ok) localLibrary.refresh() }

    val coverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        openSlug?.let { slug ->
            val cover = uri?.toString().orEmpty()
            if (cover.isNotBlank()) scope.launch { playlists.setCover(slug, cover) }
        }
    }

    LaunchedEffect(haveAudio) {
        if (haveAudio) localLibrary.refresh()
    }

    val filtered = filterSongs(songs, query)
    val showing = allPlaylists.firstOrNull { it.station.slug == openSlug }
    // Playing starts minimized; the full player only opens when the mini-player
    // bar at the bottom is tapped.
    val justPlay: (Station, List<Station>) -> Unit = { s, list -> onPlay(s, list) }

    // Pinned smart playlists — auto-populated from global state, non-removable.
    // The "local songs" row is always at the very top, above everything else.
    val smartPlaylists = listOf(
        SmartPlaylist(SmartKind.LocalSongs, filtered),
        SmartPlaylist(SmartKind.Favorites, favorites),
        SmartPlaylist(SmartKind.RecentlyPlayed, recent),
    )
    val openSmartPlaylist = smartPlaylists.firstOrNull { it.kind == openSmart }
    val paneVisible = openSmartPlaylist == null && showing == null

    val canGoBack = showProviders || showing != null || openSmartPlaylist != null || addingTo != null
    BackHandler(enabled = canGoBack) {
        when {
            addingTo != null -> addingTo = null
            showing != null -> openSlug = null
            openSmartPlaylist != null -> openSmart = null
            showProviders -> onShowProviders(false)
            else -> {}
        }
    }

    Box(Modifier.fillMaxSize().background(p.ground)) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader {
            Row(
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mono(
                    when {
                        showing != null -> showing.station.name
                        openSmartPlaylist != null -> openSmartPlaylist.label
                        showProviders -> "providers"
                        else -> "playlists"
                    },
                    CliampType.screenTitle, p.ink, maxLines = 1,
                )
            }
            if (showing == null && openSmartPlaylist == null) {
                // Sub-tabs: the library list, and a dedicated providers pane.
                Row(
                    Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Chip("playlists", selected = !showProviders, onClick = { onShowProviders(false) })
                    Chip("providers", selected = showProviders, onClick = { onShowProviders(true) })
                }
            }
            if (showing != null) {
                // playlist detail sub-header
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Chip("‹ back", selected = false, onClick = { openSlug = null })
                    Chip("add songs", selected = false, onClick = { addingTo = showing.station.slug })
                    Chip("set cover", selected = false, onClick = { coverLauncher.launch("image/*") })
                }
            } else if (openSmartPlaylist != null) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Chip("‹ back", selected = false, onClick = { openSmart = null })
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, bottom = 6.dp)
                    .clip(RoundedCornerShape(4.dp)).clickable { searchOpen = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mono("▸ ", CliampType.chip, p.inkTertiary)
                CliampTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = "search songs, artists, albums",
                    textStyle = CliampType.rowPrimary,
                    imeAction = ImeAction.Search,
                    onAction = { searchOpen = false },
                    autoFocus = searchOpen,
                )
                if (query.isNotBlank()) {
                    Icon(
                        CliampIcons.Xmark, "clear search",
                        Modifier.size(14.dp).clip(RoundedCornerShape(4.dp))
                            .clickable { query = ""; searchOpen = true }
                            .padding(2.dp),
                        tint = p.inkTertiary,
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !haveAudio -> PermissionPrompt(onGrant = { permissionLauncher.launch(audioPerm) })
                loading && songs.isEmpty() -> CenterNote("reading the library…", p.inkFaint)
                libError != null && songs.isEmpty() -> CenterNote(libError!!, p.destructiveInk)
                showProviders -> ProvidersView(
                    providers = providers,
                    onOpenProvider = onOpenProvider,
                    onAddProvider = onAddProvider,
                )
                openSmartPlaylist != null -> SmartPlaylistDetail(
                    pl = openSmartPlaylist,
                    current = current,
                    playing = playing,
                    onPlay = justPlay,
                    onToggleFavorite = onToggleFavorite,
                    favorites = favorites.map { it.url }.toSet(),
                )
                showing != null -> PlaylistDetailShown(
                    playlist = showing,
                    songIds = showing.songIds,
                    songs = songs,
                    allSongs = filtered,
                    current = current,
                    playing = playing,
                    onPlay = justPlay,
                    onRemove = { id -> scope.launch { playlists.removeSong(showing.station.slug, id) } },
                    onAdd = { id -> scope.launch { playlists.addSong(showing.station.slug, id) } },
                    adding = addingTo != null,
                    doneAdding = { addingTo = null },
                )
                else -> PlaylistList(
                    smart = smartPlaylists,
                    pinnedPlaylists = pinnedPlaylists,
                    playlists = unpinnedPlaylists,
                    query = query,
                    songs = songs,
                    searchResults = filtered,
                    current = current,
                    playing = playing,
                    onPlay = justPlay,
                    onToggleFavorite = onToggleFavorite,
                    favorites = favorites.map { it.url }.toSet(),
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
                        if (openSlug == slug) openSlug = null
                    },
                    onAddSongs = { slug -> openSlug = slug; addingTo = slug },
                    onPin = { slug, pinned -> scope.launch { playlists.setPinned(slug, pinned) } },
                    onOpen = { openSlug = it.station.slug },
                    onOpenSmart = { openSmart = it.kind },
                )
            }
        }
        }

        if (searchOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { searchOpen = false }
            )
        }

    }
}

private fun checkAudio(context: android.content.Context, perm: String): Boolean =
    context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun filterSongs(songs: List<Station>, q: String): List<Station> {
    if (q.isBlank()) return songs
    return songs.filter { s ->
        fuzzyMatch("${s.name} ${s.artist} ${s.album}", q)
    }
}

private fun filterPlaylists(playlists: List<PlaylistStore.Playlist>, q: String): List<PlaylistStore.Playlist> {
    if (q.isBlank()) return playlists
    return playlists.filter { fuzzyMatch(it.station.name, q) }
}

/**
 * Lenient, typo-tolerant search. The query is split into whitespace tokens and
 * every token must appear in the haystack as a character subsequence, so "bckst
 * wngs" still matches "Backstreets Wannabe". Tokens are tried against the whole
 * haystack rather than a single word, which keeps multi-word album/artist names
 * matchable even when the words are out of order.
 */
private fun fuzzyMatch(haystack: String, query: String): Boolean {
    val text = haystack.lowercase()
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .sortedByDescending { it.length }
    if (tokens.isEmpty()) return false
    var from = 0
    for (token in tokens) {
        val idx = subsequenceIndex(text, token, from)
        if (idx < 0) return false
        from = idx + token.length
    }
    return true
}

/** Index of [needle] as a non-contiguous subsequence of [text] at or after [from], or -1. */
private fun subsequenceIndex(text: String, needle: String, from: Int): Int {
    var ni = 0
    var i = from
    while (i < text.length && ni < needle.length) {
        if (text[i] == needle[ni]) ni++
        i++
    }
    return if (ni == needle.length) i - 1 else -1
}

@Composable
private fun CenterNote(text: String, color: androidx.compose.ui.graphics.Color) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Mono(text, CliampType.rowSecondary, color)
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(Gutter)) {
        Spacer(Modifier.height(12.dp))
        Mono("this app needs to read your audio files to show them here.", CliampType.rowPrimary, p.ink)
        Spacer(Modifier.height(4.dp))
        Mono("nothing leaves the phone. no import, no upload, no sync.", CliampType.rowSecondary, p.inkTertiary)
        Spacer(Modifier.height(14.dp))
        IconLabelButton(CliampIcons.PlayRow, "grant access", onClick = onGrant)
        Spacer(Modifier.height(12.dp))
        HairlineDivider()
    }
}

@Composable
private fun PlaylistList(
    smart: List<SmartPlaylist>,
    pinnedPlaylists: List<PlaylistStore.Playlist>,
    playlists: List<PlaylistStore.Playlist>,
    query: String,
    songs: List<Station>,
    searchResults: List<Station>,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onToggleFavorite: (Station) -> Unit,
    favorites: Set<String>,
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
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            val pinnedCount = smart.size + pinnedPlaylists.size
            item {
                SectionLabel("pinned — $pinnedCount") {
                    if (!creating) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (p.dark) p.keyFace else p.ground)
                                .border(1.dp, p.keyBorder, RoundedCornerShape(6.dp))
                                .clickable(onClick = onBeginCreate),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(CliampIcons.Plus, "new playlist", Modifier.size(16.dp), tint = p.accent)
                        }
                    }
                }
            }
            items(smart, key = { it.key }) { sp ->
                SmartPlaylistRow(sp = sp, onOpen = { onOpenSmart(sp) }, context = context)
            }

            // Search results: apart from matching playlists, surface each
            // matching song directly so a hit on a song shows the song itself.
            if (query.isNotBlank()) {
                if (searchResults.isNotEmpty()) {
                    item { SectionLabel("songs — ${searchResults.size}") }
                    items(searchResults, key = { it.url }) { s ->
                        ListRow(
                            onClick = { onPlay(s, searchResults) },
                            verticalPadding = 9.dp,
                            leading = {
                                Box(
                                    Modifier.size(28.dp).clip(RoundedCornerShape(4.dp))
                                        .then(if (current?.url == s.url) Modifier.background(p.accent)
                                              else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(4.dp))),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (current?.url == s.url && playing) CliampIcons.Pause else CliampIcons.PlayRow,
                                        "play",
                                        Modifier.size(if (current?.url == s.url && playing) 9.dp else 11.dp),
                                        tint = if (current?.url == s.url) p.onAccent else p.inkTertiary,
                                    )
                                }
                            },
                            trailing = {
                                Icon(
                                    if (s.url in favorites) CliampIcons.StarFilled else CliampIcons.Star,
                                    "favourite",
                                    Modifier.size(15.dp).clickable { onToggleFavorite(s) },
                                    tint = if (s.url in favorites) p.accent else p.inkFaint,
                                )
                            },
                        ) {
                            Mono(s.name, CliampType.rowPrimary, if (current?.url == s.url) p.accent else p.ink, maxLines = 1)
                            Mono(
                                buildList {
                                    if (s.artist.isNotBlank()) add(s.artist)
                                    if (s.album.isNotBlank()) add(s.album)
                                }.joinToString(" · ").ifBlank { s.meta },
                                CliampType.rowSecondary, p.inkTertiary, maxLines = 1,
                            )
                        }
                    }
                }
            }

            items(pinnedPlaylists, key = { it.station.slug }) { pl ->
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
                        context = context,
                    )
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
            if (playlists.isNotEmpty()) {
                item { SectionLabel("playlists — ${playlists.size}") }
            }
            items(playlists, key = { it.station.slug }) { pl ->
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
                        context = context,
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
    providers: List<ProviderAccount>,
    onOpenProvider: (ProviderAccount) -> Unit,
    onAddProvider: (ProviderSpec) -> Unit,
) {
    val p = LocalPalette.current
    val connectedKeys = providers.map { it.providerKey }.toSet()
    val available = ProviderCatalog.all.filter { it.key !in connectedKeys }
    LazyColumn(Modifier.fillMaxSize()) {
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
                            Modifier.size(28.dp).clip(RoundedCornerShape(4.dp))
                                .border(1.dp, p.chipBorder, RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(CliampIcons.Server, null, Modifier.size(14.dp), tint = p.amber)
                        }
                    },
                    trailing = { Icon(CliampIcons.CaretRight, "open", Modifier.size(11.dp), tint = p.inkTertiary) },
                ) {
                    Mono(acc.label.ifBlank { "provider" }, CliampType.rowPrimaryMedium, p.ink, maxLines = 1)
                    Mono(acc.url, CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
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
                            Modifier.size(28.dp).clip(RoundedCornerShape(4.dp))
                                .border(1.dp, p.chipBorder, RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(CliampIcons.Server, null, Modifier.size(14.dp), tint = p.inkTertiary)
                        }
                    },
                    trailing = {
                        Icon(
                            CliampIcons.Plus, "add",
                            Modifier.size(11.dp).clip(RoundedCornerShape(4.dp))
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
                Modifier.size(44.dp).clip(RoundedCornerShape(5.dp))
                    .then(if (art == null) Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(5.dp)) else Modifier),
            ) {
                if (art != null) {
                    Image(art!!, pl.station.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    StripedArt(modifier = Modifier.fillMaxSize(), radius = 5.dp, caption = null)
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
            pl.songIds.joinToString(" · ") { titleOf(it, songs) }.ifBlank { "empty playlist" },
            CliampType.rowSecondary, p.inkTertiary, maxLines = 1,
        )
    }
}

/** A pinned, auto-populated smart playlist row — always present, not removable. */
@Composable
private fun SmartPlaylistRow(
    sp: SmartPlaylist,
    onOpen: () -> Unit,
    context: android.content.Context,
) {
    val p = LocalPalette.current
    ListRow(
        onClick = onOpen,
        verticalPadding = 9.dp,
        leading = {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(5.dp))
                    .border(1.dp, p.accent.copy(alpha = 0.5f), RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when (sp.kind) {
                        SmartKind.LocalSongs -> CliampIcons.MusicNote
                        SmartKind.Favorites -> CliampIcons.Star
                        SmartKind.RecentlyPlayed -> CliampIcons.Clock
                    },
                    sp.label,
                    Modifier.size(16.dp),
                    tint = p.accent,
                )
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Mono("${sp.stations.size} items", CliampType.meta, p.inkFaint)
                Icon(CliampIcons.CaretRight, "open", Modifier.size(11.dp), tint = p.inkTertiary)
            }
        },
    ) {
        Mono(sp.label, CliampType.rowPrimaryMedium, p.ink, maxLines = 1)
        Mono(
            sp.stations.joinToString(" · ") { it.name }.ifBlank {
                if (sp.stations.isEmpty()) "nothing here yet" else ""
            },
            CliampType.rowSecondary, p.inkTertiary, maxLines = 1,
        )
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
            Modifier.size(36.dp).clip(RoundedCornerShape(5.dp)).clickable { open = true },
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
                    Modifier.width(170.dp).clip(RoundedCornerShape(6.dp))
                        .background(p.ground).border(1.dp, p.hairlineRegion, RoundedCornerShape(6.dp)),
                ) {
                    MenuItem(if (pinned) "unpin" else "pin", p.ink, onPin) { open = false }
                    HairlineDivider(region = true)
                    MenuItem("add songs", p.ink, onAddSongs) { open = false }
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
        Modifier.fillMaxWidth().clickable {
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
            .clip(RoundedCornerShape(6.dp)).border(1.dp, p.chipBorder, RoundedCornerShape(6.dp))
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
            Modifier.clip(RoundedCornerShape(4.dp)).background(p.accent.copy(alpha = 0.14f))
                .clickable { onDone(text) }.padding(horizontal = 9.dp, vertical = 7.dp))
        Mono("CANCEL", CliampType.tabLabel, p.inkTertiary,
            Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, p.chipBorder, RoundedCornerShape(4.dp))
                .clickable(onClick = onCancel).padding(horizontal = 9.dp, vertical = 7.dp))
    }
}

private fun titleOf(id: String, songs: List<Station>): String =
    songs.firstOrNull { it.id == id }?.name ?: ""

@Composable
private fun PlaylistDetailShown(
    playlist: PlaylistStore.Playlist,
    songIds: List<String>,
    songs: List<Station>,
    allSongs: List<Station>,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
    adding: Boolean,
    doneAdding: () -> Unit,
) {
    val p = LocalPalette.current
    val members = playlist.songIds.mapNotNull { id -> songs.firstOrNull { it.id == id } }

    if (adding && allSongs.isNotEmpty()) {
        // add mode: show all songs with a check affordance
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp)) {
                    Mono(playlist.station.name, CliampType.chip, p.accent)
                    Spacer(Modifier.width(8.dp))
                    Mono("· tap to add", CliampType.meta, p.inkTertiary)
                }
            }
            items(allSongs, key = { it.id }) { s ->
                val inPl = s.id in songIds
                ListRow(
                    onClick = { if (inPl) onRemove(s.id) else onAdd(s.id) },
                    verticalPadding = 9.dp,
                    leading = {
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(4.dp))
                                .then(if (inPl) Modifier.background(p.accent)
                                      else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(4.dp))),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (inPl) Icon(CliampIcons.Check, null, Modifier.size(11.dp), tint = p.onAccent)
                        }
                    },
                    trailing = { Icon(CliampIcons.Minus, "remove", Modifier.size(13.dp), if (inPl) p.accent else p.inkFaint) },
                ) {
                    Mono(s.name, CliampType.rowPrimary, p.ink, maxLines = 1)
                    Mono(if (s.artist.isNotBlank()) s.artist else s.meta, CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(Gutter).horizontalScroll(rememberScrollState())) {
                    Chip("done", selected = false, onClick = doneAdding)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        if (members.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Mono("empty — tap add songs", CliampType.rowSecondary, p.inkFaint)
                }
            }
        } else {
            item { SectionLabel("songs — ${members.size}") }
            items(members, key = { it.id }) { s ->
                ListRow(
                    onClick = { onPlay(s, members) },
                    verticalPadding = 9.dp,
                    leading = {
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(4.dp))
                                .then(if (current?.url == s.url) Modifier.background(p.accent)
                                      else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(4.dp))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (current?.url == s.url && playing) CliampIcons.Pause else CliampIcons.PlayRow,
                                null,
                                Modifier.size(if (current?.url == s.url && playing) 9.dp else 11.dp),
                                tint = if (current?.url == s.url) p.onAccent else p.inkTertiary,
                            )
                        }
                    },
                    trailing = {
                        Mono("DROP", CliampType.tabLabel, p.destructiveInk,
                            Modifier.clip(RoundedCornerShape(4.dp)).clickable { onRemove(s.id) }
                                .padding(horizontal = 8.dp, vertical = 6.dp))
                    },
                ) {
                    Mono(s.name, CliampType.rowPrimary, if (current?.url == s.url) p.accent else p.ink, maxLines = 1)
                    Mono(
                        buildList {
                            if (s.artist.isNotBlank()) add(s.artist)
                            if (s.album.isNotBlank()) add(s.album)
                        }.joinToString(" · ").ifBlank { durationLabel(s.durationMs) },
                        CliampType.rowSecondary, p.inkTertiary, maxLines = 1,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

/** Detail view for a pinned smart playlist: every member station, local and radio. */
@Composable
private fun SmartPlaylistDetail(
    pl: SmartPlaylist,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onToggleFavorite: (Station) -> Unit,
    favorites: Set<String>,
) {
    val p = LocalPalette.current
    val members = pl.stations
    LazyColumn(Modifier.fillMaxSize()) {
        if (members.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Mono(
                        when (pl.kind) {
                            SmartKind.LocalSongs -> "no songs on the phone yet"
                            SmartKind.Favorites -> "no favourites yet"
                            SmartKind.RecentlyPlayed -> "nothing played recently"
                        },
                        CliampType.rowSecondary, p.inkFaint,
                    )
                }
            }
        } else {
            item { SectionLabel("${pl.label} — ${members.size}") }
            items(members, key = { it.url }) { s ->
                ListRow(
                    onClick = { onPlay(s, members) },
                    verticalPadding = 9.dp,
                    leading = {
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(4.dp))
                                .then(if (current?.url == s.url) Modifier.background(p.accent)
                                      else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(4.dp))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (current?.url == s.url && playing) CliampIcons.Pause else CliampIcons.PlayRow,
                                null,
                                Modifier.size(if (current?.url == s.url && playing) 9.dp else 11.dp),
                                tint = if (current?.url == s.url) p.onAccent else p.inkTertiary,
                            )
                        }
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(
                                if (s.url in favorites) CliampIcons.StarFilled else CliampIcons.Star,
                                "favourite",
                                Modifier.size(15.dp).clickable { onToggleFavorite(s) },
                                tint = if (s.url in favorites) p.accent else p.inkFaint,
                            )
                        }
                    },
                ) {
                    Mono(s.name, CliampType.rowPrimary, if (current?.url == s.url) p.accent else p.ink, maxLines = 1)
                    Mono(
                        buildList {
                            if (s.artist.isNotBlank()) add(s.artist)
                            if (s.album.isNotBlank()) add(s.album)
                        }.joinToString(" · ").ifBlank { s.meta },
                        CliampType.rowSecondary, p.inkTertiary, maxLines = 1,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}