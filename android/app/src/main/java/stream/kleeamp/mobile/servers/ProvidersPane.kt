package stream.kleeamp.mobile.servers



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
import stream.kleeamp.mobile.library.PlaylistGlyph
import stream.kleeamp.mobile.library.SongCover
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
