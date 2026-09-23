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

@Composable
internal fun PlaylistList(
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

/** A playlist's identifying mark: static themed glyph on a plate, identical
 * in every theme. Deliberately not cover art - rows stay instant, with
 * nothing to load or decode. */

@Composable
internal fun PlaylistGlyph(icon: ImageVector, contentDescription: String?) {
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
