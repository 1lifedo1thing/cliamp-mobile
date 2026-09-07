package stream.cliamp.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import stream.cliamp.mobile.data.DirectoryQuery
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.LocalLibrary
import stream.cliamp.mobile.data.PlaylistStore
import stream.cliamp.mobile.data.PodcastRepository
import stream.cliamp.mobile.data.PodcastShow
import stream.cliamp.mobile.data.Repository
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.playback.PlaybackBus
import stream.cliamp.mobile.playback.PlayerConnection
import stream.cliamp.mobile.ui.components.CliampTabBar
import stream.cliamp.mobile.ui.components.CliampTabRail
import stream.cliamp.mobile.ui.components.BackPage
import stream.cliamp.mobile.ui.components.PredictiveBackSurface
import stream.cliamp.mobile.ui.components.Tab
import stream.cliamp.mobile.ui.components.TabCorners
import stream.cliamp.mobile.ui.screens.CommandScreen
import stream.cliamp.mobile.ui.screens.LocalScreen
import stream.cliamp.mobile.ui.screens.MiniPlayer
import stream.cliamp.mobile.ui.screens.NowPlayingScreen
import stream.cliamp.mobile.ui.screens.PodcastShowScreen
import stream.cliamp.mobile.ui.screens.PodcastsScreen
import stream.cliamp.mobile.ui.screens.QueueScreen
import stream.cliamp.mobile.ui.screens.ScopeScreen
import stream.cliamp.mobile.data.provider.ProviderAccount
import stream.cliamp.mobile.data.provider.ProviderCatalog
import stream.cliamp.mobile.data.provider.ProviderStore
import stream.cliamp.mobile.ui.screens.ProviderBrowseScreen
import stream.cliamp.mobile.ui.screens.ProviderWizard

import stream.cliamp.mobile.ui.screens.SettingsScreen
import stream.cliamp.mobile.ui.screens.StationsScreen
import stream.cliamp.mobile.ui.theme.LocalPalette

/** Screens that stack on top of a tab rather than replacing it. */
private sealed interface Overlay {
    data object None : Overlay
    data object Scope : Overlay
    data object Settings : Overlay
    data object Queue : Overlay
    data object Player : Overlay
    data object Command : Overlay

    /** The add-provider wizard. [account] non-null means edit rather than add. */
    data class Wizard(val providerKey: String, val account: ProviderAccount?) : Overlay

    /** Browsing one provider's library. */
    data class Browse(val accountId: String) : Overlay
}

/** Tabs within the Library screen. */
private enum class LibSubTab { Library, Providers }

@UnstableApi
@Composable
fun CliampRoot(
    repository: Repository,
    prefs: Prefs,
    player: PlayerConnection,
    localLibrary: LocalLibrary,
    playlists: PlaylistStore,
    providers: ProviderStore,
    podcasts: PodcastRepository,
    dark: Boolean,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.Stations) }
    // Overlays stack like Android pages: opening one from another (a scope
    // from the player, an edit from a browse) pushes it, and back pops to the
    // one it came from. The top is what is drawn over the tab.
    var overlayStack by remember { mutableStateOf<List<Overlay>>(emptyList()) }
    val overlay = overlayStack.lastOrNull() ?: Overlay.None
    val pushOverlay: (Overlay) -> Unit = { overlayStack = overlayStack + it }
    val popOverlay: () -> Unit = { overlayStack = overlayStack.dropLast(1) }
    // A one-shot "scroll the Stations tab down to the directory section"
    // request, raised by tapping a tag in search. Cleared once consumed.
    var focusDirectory by remember { mutableStateOf(false) }
    // Which Library sub-tab is showing. Lifted here so that closing an overlay
    // that was opened from the providers pane (add wizard or a provider's browse)
    // lands back on providers rather than the library list.
    var libSubTab by remember { mutableStateOf(LibSubTab.Library) }
    // Which podcast show's episode list is open on the Podcasts tab, and the
    // preview that drives its back gesture. The show is a pane of its tab, not
    // an overlay, so the tab strip and mini bar stay up around it; the pane
    // itself rides predictive back the way the Library panes do.
    var podsShow by remember { mutableStateOf<PodcastShow?>(null) }
    var podsPreview by remember { mutableFloatStateOf(0f) }

    val playerState by player.state.collectAsState()
    val station by PlaybackBus.station.collectAsState()
    val streamTitle by PlaybackBus.streamTitle.collectAsState()
    val favorites by prefs.favorites.collectAsState(initial = emptyList())
    val recent by prefs.history.collectAsState(initial = emptyList())
    val visualizer by prefs.visualizer.collectAsState(initial = "spectrum")
    val reconnect by PlaybackBus.reconnectAttempt.collectAsState()
    val providerAccounts by providers.accounts.collectAsState(initial = emptyList())
    val queue by player.queue.collectAsState(initial = emptyList())

    // Seed prev/next with recent history so they work from the song the mini
    // bar shows at launch, before anything has actually played this session.
    player.setFallbackSource(recent)

    val onPlay: (Station, List<Station>) -> Unit = { s, from ->
        player.play(s, from)
        repository.reportPlay(s)
    }

    // The overlays ride the back gesture to reveal this tab beneath them; as
    // one slides aside the tab scales back on the same progress, so the page
    // you are returning to previews itself the way the system back does.
    //
    // The scale is a direct 1:1 of the gesture: it tracks the finger exactly
    // during the swipe, and the surface animates the preview through its own
    // commit and revoke glides, so release and return are one motion.
    var backPreview by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(Modifier.fillMaxSize().background(p.ground)) {
        // Landscape gets a right-hand rail instead of the bottom tab strip so
        // the horizontal frame keeps its full height for content. Portrait is
        // untouched: the same bottom tabs, the same bottom mini player.
        val rail = maxWidth > maxHeight
        // The page behind the overlays: the whole shell (active tab, mini bar,
        // tab strip) stays composed and is what a back gesture previews and
        // reveals. The overlays are drawn full-screen over it.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = 0.05f * backPreview.absoluteValue
                    scaleX = 1f - s
                    scaleY = 1f - s
                },
        ) {
        Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            // The active tab stays composed regardless of which overlay is up,
            // so opening the player (or queue/scope/settings) and coming back
            // lands on the exact page you left: the Library detail, provider
            // pane, scroll and search all survive. Overlays are drawn on top;
            // each paints its own opaque surface, so they occlude cleanly.
            when (tab) {
                Tab.Stations -> StationsScreen(
                    repository = repository,
                    prefs = prefs,
                    current = station,
                    playing = playerState.playing,
                    favorites = favorites,
                    onPlay = onPlay,
                    onToggleFavorite = { s -> scope.launch { prefs.toggleFavorite(s) } },
                    onAddToQueue = { player.addToQueue(it) },
                    onPlayNext = { player.playNext(it) },
                    focusDirectory = focusDirectory,
                    onDirectoryFocusConsumed = { focusDirectory = false },
                )
                Tab.Lib -> LocalScreen(
                    localLibrary = localLibrary,
                    playlists = playlists,
                    repository = repository,
                    podcasts = podcasts,
                    current = station,
                    playing = playerState.playing,
                    favorites = favorites,
                    recent = recent,
                    onPlay = onPlay,
                    onToggleFavorite = { s -> scope.launch { prefs.toggleFavorite(s) } },
                    onAddToQueue = { player.addToQueue(it) },
                    onPlayNext = { player.playNext(it) },
                    onReplaceQueue = { s, from -> player.replaceQueue(s, from) },
                    onOpenPlayer = { pushOverlay(Overlay.Player) },
                    providers = providerAccounts,
                    showProviders = libSubTab == LibSubTab.Providers,
                    onShowProviders = { v -> libSubTab = if (v) LibSubTab.Providers else LibSubTab.Library },
                    onOpenProvider = { a ->
                        libSubTab = LibSubTab.Providers
                        pushOverlay(Overlay.Browse(a.id))
                    },
                    onAddProvider = { spec ->
                        libSubTab = LibSubTab.Providers
                        pushOverlay(Overlay.Wizard(spec.key, null))
                    },
                    onRemoveProvider = { account ->
                        // Removal takes the account's cached library and its
                        // open connections with it; ProviderStore.remove owns
                        // that. Anything already playing keeps its open handle
                        // and stops at the end of the track.
                        scope.launch { providers.remove(account.id) }
                        val open = overlay
                        if (open is Overlay.Browse && open.accountId == account.id) {
                            popOverlay()
                        }
                    },
                    backEnabled = overlay == Overlay.None,
                )
                Tab.Pods -> {
                    // Like the Library's panes: the tab holds the list behind
                    // the open show, which rides predictive back across it. The
                    // tab strip and mini bar stay composed around both, so a
                    // show reads as a page of its tab, not a full-screen cover.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(p.ground)
                            .graphicsLayer {
                                val s = 0.05f * podsPreview.absoluteValue
                                scaleX = 1f - s
                                scaleY = 1f - s
                            },
                    ) {
                        PodcastsScreen(
                            podcasts = podcasts,
                            prefs = prefs,
                            current = station,
                            playing = playerState.playing,
                            countries = repository.countries,
                            onPlay = onPlay,
                            onOpenShow = { show: PodcastShow ->
                                podcasts.openShow(show)
                                podsShow = show
                            },
                            onAddToQueue = { player.addToQueue(it) },
                            onPlayNext = { player.playNext(it) },
                        )
                    }
                    // The dim veil and the show page draw ABOVE the corner
                    // icons (clamped zIndex below), so an episode list covers
                    // the corner it came from instead of sharing it.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .zIndex(3f)
                            .graphicsLayer { alpha = 0.14f * podsPreview.absoluteValue }
                            .background(Color.Black),
                    )
                    BackPage(
                        visible = podsShow != null,
                        onBack = { podsShow = null },
                        onProgress = { podsPreview = it },
                        modifier = Modifier.zIndex(4f),
                    ) {
                        PodcastShowScreen(
                            podcasts = podcasts,
                            current = station,
                            playing = playerState.playing,
                            onBack = { podsShow = null },
                            onPlay = onPlay,
                            onAddToQueue = { player.addToQueue(it) },
                            onPlayNext = { player.playNext(it) },
                        )
                    }
                }
            }

            // The one pair of corner icons, the same for every tab: they sit
            // over the tab's list but under any deeper page. Panes raise
            // themselves above them with a clamped zIndex, so no page ever has
            // to know about this - the icons just stay composed beneath it and
            // slide back into view when it leaves.
            TabCorners(
                onOpenSearch = { pushOverlay(Overlay.Command) },
                onOpenSettings = { pushOverlay(Overlay.Settings) },
            )

            }

        // The mini bar is part of the page behind every overlay: it sits under
        // whatever is on top (the expanded player covers it) and rides with the
        // shell as a back gesture reveals it, so returning from any page lands
        // on the same shell you left, mini bar included.
        MiniPlayer(
            station = station ?: recent.firstOrNull(),
            streamTitle = streamTitle,
            playing = playerState.playing,
            buffering = playerState.buffering,
            reconnecting = reconnect,
            queueCount = queue.size,
            visualizer = visualizer,
            hasPrev = playerState.hasPrev,
            hasNext = playerState.hasNext,
            onPrev = { player.prev() },
            onNext = { player.next() },
            onOpenQueue = { pushOverlay(Overlay.Queue) },
            onToggle = { player.toggle(station ?: recent.firstOrNull()) },
            onOpen = { pushOverlay(Overlay.Player) },
        )

        if (!rail) {
            CliampTabBar(
                current = tab,
                onSelect = { tab = it; popOverlay(); podsShow = null },
            )
        }
        }
        if (rail) {
            CliampTabRail(
                current = tab,
                onSelect = { tab = it; popOverlay(); podsShow = null },
                modifier = Modifier.fillMaxHeight(),
            )
        }
        }

        // A dim veil over the whole shell so it reads as sitting "under" the
        // sheet, riding with the gesture and clearing as the cover leaves.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.14f * backPreview.absoluteValue }
                .background(Color.Black),
        )
        }

        // Overlays ride the predictive-back gesture across the whole shell: the
        // layer (active tab, mini bar, tab strip) slides aside with the finger
        // just like the settings app, then commits by popping one page off the
        // stack.
        PredictiveBackSurface(
            enabled = overlayStack.isNotEmpty(),
            onBack = { overlayStack = overlayStack.dropLast(1) },
            onProgress = { backPreview = it },
            modifier = Modifier.fillMaxSize(),
        ) {
            when (overlay) {
                Overlay.Player -> NowPlayingScreen(
                    repository = repository,
                    prefs = prefs,
                    player = player,
                    onOpenScope = { pushOverlay(Overlay.Scope) },
                    onBack = { popOverlay() },
                )
                Overlay.Queue -> QueueScreen(
                    player = player,
                    current = station,
                    playing = playerState.playing,
                    onPlay = onPlay,
                    onBack = { popOverlay() },
                )
                Overlay.Scope -> ScopeScreen(
                    prefs = prefs,
                    station = station,
                    streamTitle = streamTitle,
                    playing = playerState.playing,
                    onBack = { popOverlay() },
                )
                is Overlay.Browse -> {
                    val id = (overlay as Overlay.Browse).accountId
                    val account = providerAccounts.firstOrNull { it.id == id }
                    if (account == null) {
                        popOverlay()
                    } else {
                        ProviderBrowseScreen(
                            account = account,
                            onBack = { popOverlay() },
                            onEdit = { pushOverlay(Overlay.Wizard(account.providerKey, account)) },
                            onPlay = onPlay,
                            onOpenPlayer = { pushOverlay(Overlay.Player) },
                            onAddToQueue = { player.addToQueue(it) },
                            onPlayNext = { player.playNext(it) },
                        )
                    }
                }
                is Overlay.Wizard -> {
                    val spec = ProviderCatalog.byKey((overlay as Overlay.Wizard).providerKey)
                    if (spec == null) {
                        popOverlay()
                    } else {
                        ProviderWizard(
                            spec = spec,
                            existing = (overlay as Overlay.Wizard).account,
                            onCancel = { popOverlay() },
                            onSave = { account ->
                                scope.launch { providers.save(account) }
                                popOverlay()
                            },
                        )
                    }
                }
                Overlay.Command -> CommandScreen(
                    repository = repository,
                    podcasts = podcasts,
                    prefs = prefs,
                    localLibrary = localLibrary,
                    providers = providers,
                    onPlay = onPlay,
                    onOpenScope = { pushOverlay(Overlay.Scope) },
                    onOpenSettings = { pushOverlay(Overlay.Settings) },
                    onOpenProvider = { account ->
                        libSubTab = LibSubTab.Providers
                        pushOverlay(Overlay.Browse(account.id))
                    },
                    // Lands on the Podcasts tab behind the episode list, so
                    // backing out of the show leaves you somewhere coherent
                    // rather than on the search results you came from.
                    onOpenShow = { show: PodcastShow ->
                        podcasts.openShow(show)
                        tab = Tab.Pods
                        popOverlay()
                        podsShow = show
                    },
                    // A tag is a directory filter: land on the Stations tab so
                    // the tapping user actually sees the tagged stations rather
                    // than silently priming a list they are not looking at.
                    onOpenTag = { name ->
                        repository.loadDirectory(DirectoryQuery.Tag(name), reset = true)
                        focusDirectory = true
                        tab = Tab.Stations
                        popOverlay()
                    },
                    onBack = { popOverlay() },
                )
                Overlay.Settings -> SettingsScreen(
                    prefs = prefs,
                    repository = repository,
                    onBack = { popOverlay() },
                )
                Overlay.None -> Unit
            }
        }
    }
}
