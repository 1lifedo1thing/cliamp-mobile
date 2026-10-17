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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import stream.kleeamp.mobile.art.LocalArt
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.radio.RadioRepository
import stream.kleeamp.mobile.podcasts.ShowState
import stream.kleeamp.mobile.radio.DirectoryState
import stream.kleeamp.mobile.podcasts.EpisodeProgress
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.podcasts.downloadSizeLabel
import stream.kleeamp.mobile.servers.ProviderAccount
import stream.kleeamp.mobile.servers.ProviderCatalog
import stream.kleeamp.mobile.servers.displayName
import stream.kleeamp.mobile.servers.ProviderSpec
import stream.kleeamp.mobile.servers.SftpLibrary
import stream.kleeamp.mobile.chrome.rememberStationThumbnail
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.ChipDropdown
import stream.kleeamp.mobile.chrome.ChipOption
import stream.kleeamp.mobile.chrome.ContextMenuSheet
import stream.kleeamp.mobile.chrome.FilterRow
import stream.kleeamp.mobile.chrome.MenuAction
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.KleeampTextField
import stream.kleeamp.mobile.chrome.GlyphPlate
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.OverflowButton
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

@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
internal fun PlaylistList(
    listState: LazyListState,
    smart: List<SmartPlaylist>,
    providerAccounts: List<ProviderAccount> = emptyList(),
    onOpenProviderSongs: () -> Unit = {},
    pinnedPlaylists: List<PlaylistStore.Playlist>,
    playlists: List<PlaylistStore.Playlist>,
    creating: Boolean,
    renamingSlug: String?,
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
    // The playlist menu's subject: set by the row ⋮ trigger, cleared on dismiss.
    var menuPl by remember { mutableStateOf<Pair<PlaylistStore.Playlist, Boolean>?>(null) }
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
                    // Always rendered: hiding it while naming shrank the
                    // header and jumped the list. The dialog scrim eats taps
                    // behind it, so it cannot double-fire.
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
                PlaylistRow(
                    pl = pl,
                    onOpen = onOpen,
                    onOpenMenu = { menuPl = pl to true },
                )
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
                PlaylistRow(
                    pl = pl,
                    onOpen = onOpen,
                    onOpenMenu = { menuPl = pl to false },
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        // Naming floats centered over everything: the list never shifts.
        if (creating) {
            PlaylistNameDialog(
                placeholder = "name this playlist",
                onDone = onCreate,
                onDismiss = onCancel,
            )
        }
        renamingSlug?.let { slug ->
            val current = (pinnedPlaylists + playlists)
                .firstOrNull { it.station.slug == slug }?.station?.name.orEmpty()
            PlaylistNameDialog(
                placeholder = "name this playlist",
                initial = current,
                onDone = { onRename(slug, it) },
                onDismiss = onCancel,
            )
        }
        // The playlist menu as a bottom sheet: pin, add songs, rename and
        // remove, like the show and episode menus.
        menuPl?.let { (pl, pinned) ->
            val thumb = rememberStationThumbnail(pl.station)
            ContextMenuSheet(
                title = pl.station.name,
                subtitle = "${pl.songIds.size} songs",
                art = {
                    if (thumb != null) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(KleeampShape.small))
                                .border(1.dp, p.frameBorder, RoundedCornerShape(KleeampShape.small)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    } else {
                        PlaylistGlyph(KleeampIcons.ListShort, pl.station.name)
                    }
                },
                actions = listOf(
                    MenuAction(
                        id = "pin",
                        label = if (pinned) "unpin" else "pin",
                        subtitle = if (pinned) "remove from pinned" else "keep at the top",
                        icon = KleeampIcons.Pin,
                        onClick = { onPin(pl.station.slug, !pinned) },
                    ),
                    MenuAction(
                        id = "add",
                        label = "add songs",
                        subtitle = "pick from the phone, stations and podcasts",
                        icon = KleeampIcons.Plus,
                        onClick = { onAddSongs(pl.station.slug) },
                    ),
                    MenuAction(
                        id = "rename",
                        label = "rename",
                        subtitle = "change the playlist name",
                        icon = KleeampIcons.Pencil,
                        onClick = { onBeginRename(pl.station.slug) },
                    ),
                    MenuAction(
                        id = "remove",
                        label = "remove playlist",
                        subtitle = "songs stay on the phone",
                        icon = KleeampIcons.Trash,
                        destructive = true,
                        onClick = { onDelete(pl.station.slug) },
                    ),
                ),
                onDismiss = { menuPl = null },
            )
        }
    }
}

/** Dedicated providers pane: connected accounts, then every addable type. */

@Composable
private fun PlaylistRow(
    pl: PlaylistStore.Playlist,
    onOpen: (PlaylistStore.Playlist) -> Unit,
    onOpenMenu: () -> Unit,
) {
    val p = LocalPalette.current
    ListRow(
        onClick = { onOpen(pl) },
        verticalPadding = 9.dp,
        leading = { PlaylistLeading(pl) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Mono("${pl.songIds.size} songs", KleeampType.meta, p.inkFaint)
                OverflowButton(onOpenMenu, size = 16)
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
                    "${sp.stations.size} items",
                    KleeampType.meta, p.inkFaint,
                )
            }
        },
    ) {
        Mono(sp.label, KleeampType.rowPrimaryMedium, p.ink, maxLines = 1)
    }
}

/** A user playlist's leading mark, shared by the list and the add-to-playlist picker. */
@Composable
internal fun PlaylistLeading(pl: PlaylistStore.Playlist) {
    val p = LocalPalette.current
    // A set cover paints here; coverless playlists keep the static
    // glyph. The thumbnail resolves from memory on the first frame,
    // so rows stay instant like before.
    val thumb = rememberStationThumbnail(pl.station)
    if (thumb != null) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(KleeampShape.small))
                .border(1.dp, p.frameBorder, RoundedCornerShape(KleeampShape.small)),
            contentAlignment = Alignment.Center,
        ) {
            Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    } else {
        PlaylistGlyph(KleeampIcons.ListShort, pl.station.name)
    }
}

/** A playlist's identifying mark: static themed glyph on a plate, identical
 * in every theme. Deliberately not cover art - rows stay instant, with
 * nothing to load or decode. */
@Composable
internal fun PlaylistGlyph(icon: ImageVector, contentDescription: String?) {
    GlyphPlate(icon, contentDescription, Modifier.size(44.dp))
}

/** The smart playlist's identifying glyph. */
internal fun smartKindIcon(kind: SmartKind): ImageVector = when (kind) {
    SmartKind.LocalSongs -> KleeampIcons.MusicNote
    SmartKind.Downloads -> KleeampIcons.Download
    SmartKind.Favorites -> KleeampIcons.Heart
    SmartKind.RecentlyPlayed -> KleeampIcons.Clock
}

/** A playlist naming dialog, centered and frozen: one compact row (field +
 * SAVE/CANCEL side by side) that clears the keys without any pan, inset
 * tracking or movement. The IME Done key saves, so typing never depends on
 * seeing the buttons. The store still trims and ignores blanks. */
@Composable
internal fun PlaylistNameDialog(
    placeholder: String,
    initial: String = "",
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf(initial) }
    // Lift only when the keys would cover the card, animated both ways: up
    // as they open, back down as they close. Otherwise the card never moves.
    // The window takes its own insets so the platform never pans it.
    val density = LocalDensity.current
    var cardHpx by remember { mutableStateOf(0) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(
            // Full-size so the lift math sees the screen; it also eats the
            // dialog's own outside-tap dismiss, so taps here dismiss and the
            // card below consumes its own.
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
            contentAlignment = Alignment.Center,
        ) {
            val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
            val liftTarget = with(density) {
                val unliftedBottom = maxHeight / 2 + cardHpx.toDp() / 2
                val keysTop = maxHeight - imeBottom
                (unliftedBottom - keysTop + 12.dp).coerceAtLeast(0.dp)
            }
            val lift by animateDpAsState(liftTarget, label = "namingLift")
            Row(
                Modifier.fillMaxWidth()
                    .offset(y = -lift)
                    .onGloballyPositioned { cardHpx = it.size.height }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .clip(RoundedCornerShape(KleeampShape.small))
                    .border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.small))
                    .background(p.panel).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            KleeampTextField(
                value = text,
                onValueChange = { text = it.take(48) },
                modifier = Modifier.weight(1f),
                placeholder = placeholder,
                textStyle = KleeampType.rowPrimary,
                autoFocus = true,
                onAction = { onDone(text) },
            )
            Mono("SAVE", KleeampType.tabLabel, p.accent,
                Modifier.clip(RoundedCornerShape(KleeampShape.tiny)).background(p.accent.copy(alpha = 0.14f))
                    .microPress { onDone(text) }.padding(horizontal = 9.dp, vertical = 7.dp))
            Mono("CANCEL", KleeampType.tabLabel, p.inkTertiary,
                Modifier
                    .clip(RoundedCornerShape(KleeampShape.tiny))
                    .border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.tiny))
                    .microPress(onClick = onDismiss).padding(horizontal = 9.dp, vertical = 7.dp))
            }
        }
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
