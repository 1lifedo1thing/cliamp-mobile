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

/** One song's full detail as a navigation pane. */

@Composable
fun LibrarySongInfoPane(
    vm: SongInfoViewModel,
    stationUrl: String,
    repository: RadioRepository,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val ui by vm.state.collectAsState()
    val songs = ui.songs
    val favorites = ui.favorites
    val recent = ui.recent
    val cliamp by repository.cliamp.collectAsState(initial = emptyList())
    val directory by repository.directory.collectAsState(initial = DirectoryState())
    // Playlist snapshots last, like the add-to-playlist picker: a member
    // that was never favourited or played still resolves.
    val snapshot = ui.snapshot
    val station = remember(stationUrl, songs, favorites, recent, cliamp, directory, snapshot) {
        (songs + favorites + recent + cliamp + directory.stations + listOfNotNull(snapshot))
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
    // File rows only read when there is a real file: streams would show a
    // zero size and a URL for a location.
    val isFile = s.url.startsWith("file://")
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
                            SeedPlate(
                                key = s.id.ifBlank { s.url },
                                name = s.name,
                                modifier = Modifier.fillMaxSize(),
                                radius = KleeampShape.large,
                            )
                        }
                        if (favorite) {
                            Mono(
                                "♥ favourite", KleeampType.chip, p.onAccent,
                                Modifier.align(Alignment.TopStart).padding(8.dp)
                                    .clip(RoundedCornerShape(KleeampShape.tiny))
                                    .background(p.accent.copy(alpha = 0.92f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                Column(
                    Modifier.padding(horizontal = Gutter, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Mono(s.name, KleeampType.trackTitleCompact, p.ink, maxLines = 2)
                    Mono(s.artist.ifBlank { "unknown artist" }, KleeampType.rowSecondary, p.inkTertiary)
                    if (s.album.isNotBlank()) Mono(s.album, KleeampType.body, p.inkTertiary)
                }

                Column(
                    Modifier.fillMaxWidth().padding(Gutter),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    SongInfoRow("duration", durationLabel(s.durationMs))
                    if (isFile) {
                        SongInfoRow("added", added)
                    }
                    SongInfoRow("plays", if (plays > 0) "$plays" else "—")
                    SongInfoRow(
                        "last played",
                        if (lastPlayedAt > 0) SimpleDateFormat("dd MMM yyyy", Locale.US)
                            .format(Date(lastPlayedAt)) else "—",
                    )
                    if (isFile) {
                        SongInfoRow("size", sizeLabel)
                        SongInfoRow("format", File(path).extension.uppercase().ifBlank { "—" })
                        SongInfoRow("location", File(path).parent.orEmpty())
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Actions: favourite toggle, plus the destructive remove for
                // on-device files only - streams have nothing to delete.
                Row(Modifier.fillMaxWidth().padding(Gutter)) {
                    Row(
                        Modifier
                            .padding(end = if (isFile) 4.dp else 0.dp)
                            .weight(1f)
                            .clip(RoundedCornerShape(KleeampShape.small))
                            .background(p.panel)
                            .microPress { onToggleFavorite(s) }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (favorite) KleeampIcons.HeartFilled else KleeampIcons.Heart,
                            "favourite",
                            Modifier.size(15.dp).padding(end = 6.dp),
                            tint = if (favorite) p.accent else p.inkTertiary,
                        )
                        Mono(if (favorite) "favourited" else "favourite", KleeampType.chip, p.ink)
                    }
                    if (isFile) {
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
