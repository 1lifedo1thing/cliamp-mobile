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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import stream.cliamp.mobile.data.StationArtSource
import stream.cliamp.mobile.data.LocalLibrary
import stream.cliamp.mobile.data.PlaylistStore
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.durationLabel
import stream.cliamp.mobile.data.provider.ProviderAccount
import stream.cliamp.mobile.data.provider.ProviderCatalog
import stream.cliamp.mobile.data.provider.ProviderSpec
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.CliampTextField
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.OverflowButton
import stream.cliamp.mobile.ui.components.OverflowItem
import stream.cliamp.mobile.ui.components.OverflowMenu
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
    onPlayNext: (Station) -> Unit,
    onReplaceQueue: (Station, List<Station>) -> Unit = { s, _ -> onPlay(s, emptyList()) },
    onOpenPlayer: () -> Unit,
    providers: List<ProviderAccount> = emptyList(),
    showProviders: Boolean = false,
    onShowProviders: (Boolean) -> Unit = {},
    onOpenProvider: (ProviderAccount) -> Unit = {},
    onAddProvider: (ProviderSpec) -> Unit = {},
    // False while an overlay (player, queue, settings…) is on top of this tab.
    // The tab stays composed underneath so its navigation state survives, but
    // its own back handling must stand down or it would steal the back press
    // from the overlay.
    backEnabled: Boolean = true,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

    val filtered = songs
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
    BackHandler(enabled = backEnabled && canGoBack) {
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
                        else -> "Library"
                    },
                    CliampType.screenTitle, p.ink, maxLines = 1,
                )
            }
            if (showing == null && openSmartPlaylist == null) {
                // Sub-tabs: the library list, and a dedicated providers pane.
                // A little top padding keeps them from sticking to the title.
                Row(
                    Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 10.dp),
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
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !haveAudio -> PermissionNote()
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
                    loading = loading,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onReplaceQueue = onReplaceQueue,
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
                    songs = songs,
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
                    loading = loading,
                )
            }
        }
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
    smart: List<SmartPlaylist>,
    pinnedPlaylists: List<PlaylistStore.Playlist>,
    playlists: List<PlaylistStore.Playlist>,
    songs: List<Station>,
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
    loading: Boolean = false,
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
                SmartPlaylistRow(sp = sp, onOpen = { onOpenSmart(sp) }, context = context, loading = loading)
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
            playlistPreview(pl.songIds, byId).ifBlank { "empty playlist" },
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
    loading: Boolean = false,
) {
    val p = LocalPalette.current
    val scanning = loading && sp.kind == SmartKind.LocalSongs && sp.stations.isEmpty()
    val pulse = rememberInfiniteTransition(label = "scan")
    val shimmer by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "shimmer",
    )
    ListRow(
        onClick = onOpen,
        verticalPadding = 9.dp,
        leading = {
            Box(
                Modifier.size(44.dp)
                    .then(
                        if (scanning)
                            Modifier.clip(RoundedCornerShape(5.dp)).background(p.inkFaint.copy(alpha = 0.35f * shimmer))
                        else
                            Modifier.border(1.dp, p.accent.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (!scanning) {
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
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (scanning) {
                    Box(Modifier.width(52.dp).height(12.dp).clip(RoundedCornerShape(6.dp))
                        .background(p.inkFaint.copy(alpha = 0.35f * shimmer)))
                } else {
                    Mono("${sp.stations.size} items", CliampType.meta, p.inkFaint)
                }
                Icon(CliampIcons.CaretRight, "open", Modifier.size(11.dp), tint = p.inkTertiary)
            }
        },
    ) {
        Mono(sp.label, CliampType.rowPrimaryMedium, p.ink, maxLines = 1)
        if (scanning) {
            Box(Modifier.width(150.dp).height(12.dp).clip(RoundedCornerShape(6.dp))
                .background(p.inkFaint.copy(alpha = 0.35f * shimmer)))
        } else {
            Mono(
                smartPreview(sp.stations).ifBlank {
                    if (sp.stations.isEmpty()) "nothing here yet" else ""
                },
                CliampType.rowSecondary, p.inkTertiary, maxLines = 1,
            )
        }
    }
}

/** The ⋮ overflow menu on a song row: play next, add to the queue, or replace the queue. */
@Composable
private fun SongRowMenu(
    onPlayNext: () -> Unit,
    onAddEnd: () -> Unit,
    onReplace: () -> Unit,
) {
    OverflowMenu(
        trigger = { open -> OverflowButton(open) },
        items = listOf(
            OverflowItem("play next", onPlayNext),
            OverflowItem("add to queue", onAddEnd),
            OverflowItem("replace queue", onReplace),
        ),
    )
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

/**
 * Preview line under a smart-playlist row. Joining every member name would
 * build a ~hundreds-of-KB string on every recomposition (e.g. "local songs"
 * with a big library), which is what made the Library tab take seconds to
 * respond to touch. Cap it to a few names plus a "+N" suffix.
 */
private fun smartPreview(stations: List<Station>): String {
    if (stations.isEmpty()) return ""
    val head = 4
    val names = stations.take(head).joinToString(" · ") { it.name }
    if (stations.size <= head) return names
    return "$names · +${stations.size - head} more"
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
                        SongCover(s = s, current = current, playing = playing)
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
    loading: Boolean = false,
    onPlayNext: (Station) -> Unit = {},
    onAddToQueue: (Station) -> Unit = {},
    onReplaceQueue: (Station, List<Station>) -> Unit = { s, _ -> onPlay(s, emptyList()) },
) {
    val p = LocalPalette.current
    val members = pl.stations
    LazyColumn(Modifier.fillMaxSize()) {
        if (members.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Mono(
                        when (pl.kind) {
                            SmartKind.LocalSongs ->
                                if (loading) "scanning for songs…" else "no songs on the phone yet"
                            SmartKind.Favorites -> "no favourites yet"
                            SmartKind.RecentlyPlayed -> "nothing played recently"
                        },
                        CliampType.rowSecondary, p.inkFaint,
                    )
                }
            }
        } else {
            item { SectionLabel("${pl.label} — ${members.size}") }
            items(members, key = { it.url }, contentType = { "local-song" }) { s ->
                ListRow(
                    onClick = { onPlay(s, members) },
                    verticalPadding = 9.dp,
                    leading = {
                        SongCover(s = s, current = current, playing = playing)
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
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (art != null) Modifier.background(p.panel)
                else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(6.dp))
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
                    .clip(RoundedCornerShape(4.dp))
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