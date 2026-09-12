package stream.cliamp.mobile.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import androidx.navigation.toRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.DirectoryQuery
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.PodcastRepository
import stream.cliamp.mobile.data.PodcastShow
import stream.cliamp.mobile.data.Repository
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.playback.PlaybackBus
import stream.cliamp.mobile.playback.PlayerConnection
import stream.cliamp.mobile.ui.components.CliampTabBar
import stream.cliamp.mobile.ui.components.CliampTabRail
import stream.cliamp.mobile.ui.components.MiniPlayer
import stream.cliamp.mobile.ui.components.Tab
import stream.cliamp.mobile.ui.screens.SearchScreen
import stream.cliamp.mobile.ui.screens.FavScope
import stream.cliamp.mobile.ui.screens.LibraryPlaylistPane
import stream.cliamp.mobile.ui.screens.LibraryProvidersPane
import stream.cliamp.mobile.ui.screens.LibrarySmartPlaylistPane
import stream.cliamp.mobile.ui.screens.LibrarySongInfoPane
import stream.cliamp.mobile.ui.screens.LocalScreen
import stream.cliamp.mobile.ui.screens.NowPlayingScreen
import stream.cliamp.mobile.ui.screens.PodcastShowScreen
import stream.cliamp.mobile.ui.screens.PodcastsScreen
import stream.cliamp.mobile.ui.screens.QueueScreen
import stream.cliamp.mobile.ui.screens.ScopeScreen
import stream.cliamp.mobile.ui.screens.ScrobbleWizard as ScrobbleWizardScreen
import stream.cliamp.mobile.data.provider.ProviderCatalog
import stream.cliamp.mobile.data.provider.ProviderStore
import stream.cliamp.mobile.ui.screens.ProviderBrowseScreen
import stream.cliamp.mobile.ui.screens.ProviderWizard as ProviderWizardScreen
import stream.cliamp.mobile.ui.screens.SettingsScreen
import stream.cliamp.mobile.ui.screens.StationsScreen
import stream.cliamp.mobile.ui.screens.StationsViewModel
import stream.cliamp.mobile.ui.screens.PodcastsViewModel
import stream.cliamp.mobile.ui.screens.PodcastShowViewModel
import stream.cliamp.mobile.ui.screens.LocalViewModel
import stream.cliamp.mobile.ui.screens.ProvidersPaneViewModel
import stream.cliamp.mobile.ui.screens.SmartPlaylistViewModel
import stream.cliamp.mobile.ui.screens.PlaylistDetailViewModel
import stream.cliamp.mobile.ui.screens.SongInfoViewModel
import stream.cliamp.mobile.ui.screens.NowPlayingViewModel
import stream.cliamp.mobile.ui.screens.SearchViewModel
import stream.cliamp.mobile.ui.screens.SettingsViewModel
import stream.cliamp.mobile.ui.screens.ProviderBrowseViewModel
import stream.cliamp.mobile.ui.screens.ProviderWizardViewModel
import stream.cliamp.mobile.ui.screens.ScrobbleWizardViewModel
import stream.cliamp.mobile.ui.theme.LocalPalette

/**
 * Forward push: instant, no transition. Only the way back animates.
 */
private val NoPush = EnterTransition.None

/**
 * Back pop: the front page slides back out to the right (finger-driven on a
 * predictive gesture, animated on a button press) while the page beneath
 * scales back up from slightly small - the native slide+scale return.
 */
private val PagePopExit =
    slideOutHorizontally(tween(280)) { it }
private val PagePopEnter =
    scaleIn(tween(280), initialScale = 0.92f)

/** Tab roots switch instantly and never slide away; they only scale back in. */
private fun rootEnter(): EnterTransition = EnterTransition.None
private fun rootExit(): ExitTransition = ExitTransition.None

@UnstableApi
@Composable
fun CliampRoot(
    repository: Repository,
    prefs: Prefs,
    player: PlayerConnection,
    providers: ProviderStore,
    podcasts: PodcastRepository,
    dark: Boolean,
    /** Bumped by MainActivity whenever the search widget (or any
     * OPEN_SEARCH intent) asks for the Search page. */
    openSearchTick: Int = 0,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()

    // The active tab, or null when a search-widget launch started directly
    // on Search: no tab has been visited yet, so none reads as selected.
    var tab by remember { mutableStateOf<Tab?>(if (openSearchTick > 0) null else Tab.Stations) }
    var focusDirectory by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // True for a beat after any tab-bar tap: pop transitions go flat so a
    // switch never flashes the intermediate page's slide+scale. Tab-bar
    // travel is always instant; only a real back animates.
    var calmNav by remember { mutableStateOf(false) }
    var calmJob by remember { mutableStateOf<Job?>(null) }
    // The favourites type filter, shared by the library list and the
    // favourites smart detail pane so both agree.
    var favScope by rememberSaveable { mutableStateOf(FavScope.All) }

    val playerState by player.state.collectAsState()
    val station by PlaybackBus.station.collectAsState()
    val streamTitle by PlaybackBus.streamTitle.collectAsState()
    val favorites by prefs.favorites.collectAsState(initial = emptyList())
    val recent by prefs.history.collectAsState(initial = emptyList())
    val visualizer by prefs.visualizer.collectAsState(initial = "spectrum")
    val reconnect by PlaybackBus.reconnectAttempt.collectAsState()
    val providerAccounts by providers.accounts.collectAsState(initial = emptyList())
    val progress by podcasts.progress.collectAsState(initial = emptyMap())

    player.setFallbackSource(recent)

    // Search-widget deep link while running: any OPEN_SEARCH tick opens the
    // Search overlay, unless it is already on top. launchSingleTop keeps
    // rapid double-taps from stacking two copies. The cold-start case needs
    // no navigation: the NavHost below starts directly on Search, so there
    // is no one-frame flash of Stations first.
    LaunchedEffect(openSearchTick) {
        if (openSearchTick > 0) {
            val route = navController.currentDestination?.route
            if (route?.startsWith(Search::class.qualifiedName!!) != true) {
                navController.navigate(Search) {
                    launchSingleTop = true
                }
            }
        }
    }

    val onPlay: (Station, List<Station>) -> Unit = { s, from ->
        player.play(s, from)
        repository.reportPlay(s)
    }

    // Switch tabs, clearing any overlay destinations from the back stack.
    // Always instant: the calm window flattens pop transitions so going to
    // a tab from the tabs never plays the intermediate page's animation.
    val switchTab: (Tab) -> Unit = { newTab ->
        calmJob?.cancel()
        calmNav = true
        calmJob = scope.launch {
            delay(350)
            calmNav = false
        }
        if (newTab == tab) {
            // Tapping the current tab: pop to its root if drilled down.
            val root = when (newTab) {
                Tab.Stations -> StationsRoot
                Tab.Pods -> PodcastsRoot
                Tab.Lib -> LibraryRoot
            }
            navController.popBackStack(root, false)
        } else {
            // Switching tabs: pop to the current tab's root (clearing overlays
            // and panes), then leave the whole current tab graph behind -
            // popped inclusive with its state saved - so the new tab is the
            // only graph on the stack. Back on any tab root then has nothing
            // to pop and exits natively, the way the start tab always did.
            //
            // Null means a search-widget launch is sitting on Search with no
            // tab visited yet: drop it and enter the picked tab fresh.
            val prev = tab
            tab = newTab
            val dest = when (newTab) {
                Tab.Stations -> StationsTab
                Tab.Pods -> PodcastsTab
                Tab.Lib -> LibraryTab
            }
            if (prev == null) {
                navController.popBackStack()
                navController.navigate(dest) {
                    launchSingleTop = true
                    restoreState = true
                }
            } else {
                val currentRoot = when (prev) {
                    Tab.Stations -> StationsRoot
                    Tab.Pods -> PodcastsRoot
                    Tab.Lib -> LibraryRoot
                }
                val currentGraphRoute = when (prev) {
                    Tab.Stations -> StationsTab::class.qualifiedName!!
                    Tab.Pods -> PodcastsTab::class.qualifiedName!!
                    Tab.Lib -> LibraryTab::class.qualifiedName!!
                }
                navController.popBackStack(currentRoot, false)
                navController.navigate(dest) {
                    popUpTo(currentGraphRoute) {
                        inclusive = true
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    // The chrome (mini player + tab strip) lives under every page for the
    // whole session: it is never removed, so there is no flash of it
    // disappearing when an overlay opens. Full overlays cover it at rest and
    // a back just slides them away to show it - tabs, mini player and all.
    // Guarded opens: tapping the mini player while already on that page is a
    // no-op instead of stacking a duplicate destination.
    val openPlayer: () -> Unit = {
        if (currentRoute?.startsWith(Player::class.qualifiedName!!) != true) {
            navController.navigate(Player)
        }
    }
    val openQueue: () -> Unit = {
        if (currentRoute?.startsWith(Queue::class.qualifiedName!!) != true) {
            navController.navigate(Queue)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(p.ground)) {
        val rail = maxWidth > maxHeight
        val density = LocalDensity.current

        // Measured chrome size, so tab pages, panes and Settings end above
        // the mini player + tab strip instead of sliding underneath them.
        var chromeBottom by remember { mutableStateOf(0.dp) }
        var chromeEnd by remember { mutableStateOf(0.dp) }
        val contentEnd = if (rail) chromeEnd else 0.dp
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(bottom = chromeBottom, end = contentEnd)

        // Permanent chrome, bottom z: always composed underneath, never
        // removed. Tab pages, panes and Settings leave its zone empty (they
        // end above it) so it shows; full overlays below paint the whole
        // frame opaque so the chrome is fully under them. A back just slides
        // the page away to reveal it - tabs, mini player and all, instantly.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(end = contentEnd)
                .onSizeChanged { chromeBottom = with(density) { it.height.toDp() } },
        ) {
            MiniPlayer(
                station = station ?: recent.firstOrNull(),
                streamTitle = streamTitle,
                playing = playerState.playing,
                buffering = playerState.buffering,
                    reconnecting = reconnect,
                    visualizer = visualizer,
                hasPrev = playerState.hasPrev,
                hasNext = playerState.hasNext,
                onPrev = { player.prev() },
                onNext = { player.next() },
                onOpenQueue = openQueue,
                onToggle = { player.toggle(station ?: recent.firstOrNull()) },
                onOpen = openPlayer,
            )

            if (!rail) {
                CliampTabBar(
                    current = tab,
                    onSelect = switchTab,
                )
            }
        }
        if (rail) {
            CliampTabRail(
                current = tab,
                onSelect = switchTab,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    .onSizeChanged { chromeEnd = with(density) { it.width.toDp() } },
            )
        }

        // Single navigation owner: tab roots, tab panes and full overlays all
        // live in this host, so every back - overlay or pane - plays the same
        // slide-out + scale-in transition, finger-driven on a gesture.
        NavHost(
            navController = navController,
            // A search-widget launch starts directly on Search: no one-frame
            // flash of Stations first. Back from there falls through to the
            // Stations tab (see the Search onBack below).
            startDestination = if (openSearchTick > 0) Search else StationsTab,
            // Transparent: the chrome underneath shows through the padded
            // zone; every overlay paints its own opaque cover instead.
            modifier = Modifier.fillMaxSize(),
            enterTransition = { NoPush },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { if (calmNav) EnterTransition.None else PagePopEnter },
            popExitTransition = { if (calmNav) ExitTransition.None else PagePopExit },
        ) {
            navigation<StationsTab>(startDestination = StationsRoot) {
                composable<StationsRoot>(
                    enterTransition = { rootEnter() },
                    exitTransition = { rootExit() },
                    popEnterTransition = { if (calmNav) EnterTransition.None else PagePopEnter },
                    popExitTransition = { ExitTransition.None },
                ) {
                    Box(contentModifier) {
                        StationsScreen(
                            vm = appViewModel { app -> StationsViewModel(app.repository, app.prefs) },
                            current = station,
                            playing = playerState.playing,
                            favorites = favorites,
                            onPlay = onPlay,
                            onOpenSearch = {
                                navController.navigate(Search)
                            },
                            onOpenSettings = {
                                navController.navigate(Settings)
                            },
                            focusDirectory = focusDirectory,
                            onDirectoryFocusConsumed = { focusDirectory = false },
                        )
                    }
                }
            }
            navigation<PodcastsTab>(startDestination = PodcastsRoot) {
                composable<PodcastsRoot>(
                    enterTransition = { rootEnter() },
                    exitTransition = { rootExit() },
                    popEnterTransition = { if (calmNav) EnterTransition.None else PagePopEnter },
                    popExitTransition = { ExitTransition.None },
                ) {
                    Box(contentModifier) {
                        PodcastsScreen(
                            vm = appViewModel { app ->
                                PodcastsViewModel(app.podcasts, app.prefs, app.repository.countries)
                            },
                            onOpenShow = { show: PodcastShow ->
                                podcasts.openShow(show)
                                navController.navigate(PodcastShowRoute(show.id))
                            },
                            onOpenSearch = { navController.navigate(Search) },
                            onOpenSettings = { navController.navigate(Settings) },
                        )
                    }
                }
                composable<PodcastShowRoute> {
                    Box(contentModifier) {
                        PodcastShowScreen(
                            vm = appViewModel { app ->
                                PodcastShowViewModel(app.podcasts, app.prefs, app.downloads)
                            },
                            current = station,
                            playing = playerState.playing,
                            onBack = { navController.popBackStack() },
                            onPlay = onPlay,
                            onAddToQueue = { player.addToQueue(it) },
                            onPlayNext = { player.playNext(it) },
                            onOpenSearch = { navController.navigate(Search) },
                            onOpenSettings = { navController.navigate(Settings) },
                        )
                    }
                }
            }
            navigation<LibraryTab>(startDestination = LibraryRoot) {
                composable<LibraryRoot>(
                    enterTransition = { rootEnter() },
                    exitTransition = { rootExit() },
                    popEnterTransition = { if (calmNav) EnterTransition.None else PagePopEnter },
                    popExitTransition = { ExitTransition.None },
                ) {
                    Box(contentModifier) {
                        LocalScreen(
                            vm = appViewModel { app ->
                                LocalViewModel(app.localLibrary, app.playlists, app.prefs)
                            },
                            onOpenProviders = { navController.navigate(LibraryProviders) },
                            onOpenSmart = { kind -> navController.navigate(LibrarySmartPlaylist(kind)) },
                            onOpenPlaylist = { slug -> navController.navigate(LibraryPlaylist(slug)) },
                            onOpenSearch = { navController.navigate(Search) },
                            onOpenSettings = { navController.navigate(Settings) },
                            favScope = favScope,
                        )
                    }
                }
                composable<LibraryProviders> {
                    Box(contentModifier) {
                        LibraryProvidersPane(
                            vm = appViewModel { app -> ProvidersPaneViewModel(app.providers) },
                            onBack = { navController.popBackStack() },
                            onOpenProvider = { a -> navController.navigate(ProviderBrowse(a.id)) },
                            onAddProvider = { spec ->
                                navController.navigate(ProviderWizardRoute(spec.key))
                            },
                            onOpenSearch = { navController.navigate(Search) },
                            onOpenSettings = { navController.navigate(Settings) },
                        )
                    }
                }
                composable<LibrarySmartPlaylist> { entry ->
                    val kind = entry.toRoute<LibrarySmartPlaylist>().kind
                    Box(contentModifier) {
                        LibrarySmartPlaylistPane(
                            vm = appViewModel(key = kind) { app ->
                                SmartPlaylistViewModel(kind, app.localLibrary, app.prefs, app.downloads)
                            },
                            kindName = kind,
                            current = station,
                            playing = playerState.playing,
                            onPlay = onPlay,
                            favScope = favScope,
                            onFavScopeChange = { favScope = it },
                            onOpenSongInfo = { s -> navController.navigate(LibrarySongInfo(s.url)) },
                            onBack = { navController.popBackStack() },
                            onOpenSearch = { navController.navigate(Search) },
                            onOpenSettings = { navController.navigate(Settings) },
                            progress = progress,
                        )
                    }
                }
                composable<LibraryPlaylist> { entry ->
                    val slug = entry.toRoute<LibraryPlaylist>().slug
                    Box(contentModifier) {
                        LibraryPlaylistPane(
                            vm = appViewModel(key = slug) { app ->
                                PlaylistDetailViewModel(
                                    slug,
                                    app.localLibrary,
                                    app.playlists,
                                    app.prefs,
                                    app.repository,
                                    app.podcasts,
                                    app.downloads,
                                )
                            },
                            slug = slug,
                            current = station,
                            playing = playerState.playing,
                            onPlay = onPlay,
                            onBack = { navController.popBackStack() },
                            onOpenSearch = { navController.navigate(Search) },
                            onOpenSettings = { navController.navigate(Settings) },
                        )
                    }
                }
                composable<LibrarySongInfo> { entry ->
                    val stationUrl = entry.toRoute<LibrarySongInfo>().stationUrl
                    Box(contentModifier) {
                        LibrarySongInfoPane(
                            vm = appViewModel(key = stationUrl) { app ->
                                SongInfoViewModel(stationUrl, app.localLibrary, app.prefs, app.scrobbler)
                            },
                            stationUrl = stationUrl,
                            repository = repository,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }

            // -- Full overlay destinations (cover the chrome) --
            composable<Player> {
                OverlayCover {
                NowPlayingScreen(
                    vm = appViewModel { app -> NowPlayingViewModel(app.player, app.prefs) },
                    onOpenScope = { navController.navigate(Scope) },
                    onBack = { navController.popBackStack() },
                )
                }
            }
            composable<Queue> {
                OverlayCover {
                QueueScreen(
                    player = player,
                    current = station,
                    playing = playerState.playing,
                    onPlay = onPlay,
                    onBack = { navController.popBackStack() },
                )
                }
            }
            composable<Scope> {
                OverlayCover {
                ScopeScreen(
                    prefs = prefs,
                    station = station,
                    streamTitle = streamTitle,
                    playing = playerState.playing,
                    onBack = { navController.popBackStack() },
                )
                }
            }
            composable<Settings> {
                Box(contentModifier) {
                    SettingsScreen(
                        vm = appViewModel { app -> SettingsViewModel(app.prefs, app.repository) },
                        onBack = { navController.popBackStack() },
                        onOpenSearch = { navController.navigate(Search) },
                        onOpenScrobble = { navController.navigate(ScrobbleWizard) },
                    )
                }
            }
            composable<Search> {
                Box(contentModifier) {
                    SearchScreen(
                    vm = appViewModel { app ->
                        SearchViewModel(
                            app.repository,
                            app.podcasts,
                            app.prefs,
                            app.localLibrary,
                            app.providers,
                        )
                    },
                    current = station,
                    playing = playerState.playing,
                    onPlay = onPlay,
                    onOpenProvider = { account ->
                        navController.navigate(ProviderBrowse(account.id))
                    },
                    onOpenShow = { show: PodcastShow ->
                        podcasts.openShow(show)
                        // Pop the search overlay, switch to podcasts tab,
                        // and navigate to the show.
                        navController.popBackStack()
                        switchTab(Tab.Pods)
                        navController.navigate(PodcastShowRoute(show.id))
                    },
                    onOpenTag = { name ->
                        repository.loadDirectory(DirectoryQuery.Tag(name), reset = true)
                        focusDirectory = true
                        navController.popBackStack()
                        switchTab(Tab.Stations)
                    },
                    onBack = {
                        // Normally pops back to the tab underneath. When a
                        // search-widget launch started directly on Search
                        // there is nothing to pop: fall through to Stations
                        // and mark it visited so it reads as selected.
                        if (!navController.popBackStack()) {
                            tab = Tab.Stations
                            navController.navigate(StationsTab) {
                                launchSingleTop = true
                            }
                        }
                    },
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                )
                }
            }
            composable<ProviderBrowse> { entry ->
                val accountId = entry.toRoute<ProviderBrowse>().accountId
                val account = providerAccounts.firstOrNull { it.id == accountId }
                if (account == null) {
                    navController.popBackStack()
                } else {
                    OverlayCover {
                    ProviderBrowseScreen(
                        vm = appViewModel(key = accountId) { _ -> ProviderBrowseViewModel(account) },
                        onBack = { navController.popBackStack() },
                        onEdit = {
                            navController.navigate(
                                ProviderWizardRoute(account.providerKey, account.id)
                            )
                        },
                        onPlay = onPlay,
                        onOpenPlayer = openPlayer,
                    )
                    }
                }
            }
            composable<ProviderWizardRoute> { entry ->
                val route = entry.toRoute<ProviderWizardRoute>()
                val spec = ProviderCatalog.byKey(route.providerKey)
                if (spec == null) {
                    navController.popBackStack()
                } else {
                    val existing = if (route.accountId.isNotEmpty()) {
                        providerAccounts.firstOrNull { it.id == route.accountId }
                    } else null
                    OverlayCover {
                    ProviderWizardScreen(
                        vm = appViewModel(key = route.providerKey + route.accountId) { _ ->
                            ProviderWizardViewModel(spec, existing)
                        },
                        onCancel = { navController.popBackStack() },
                        onSave = { account ->
                            scope.launch { providers.save(account) }
                            navController.popBackStack()
                        },
                    )
                    }
                }
            }
            composable<ScrobbleWizard> {
                val token by prefs.listenBrainzToken.collectAsState(initial = "")
                OverlayCover {
                    ScrobbleWizardScreen(
                        vm = appViewModel { _ -> ScrobbleWizardViewModel(token) },
                        onCancel = { navController.popBackStack() },
                        onSave = { t ->
                            scope.launch { prefs.setListenBrainzToken(t) }
                            navController.popBackStack()
                        },
                    )
                }
            }
        }

    }
}

/**
 * Full-screen cover for overlay destinations. The chrome lives underneath
 * for the whole session, so this paints the frame opaque (keeping tabs and
 * mini player fully under the overlay) and swallows taps on empty areas so
 * they cannot fall through to the chrome. Taps give no visual: null.
 */
@Composable
private fun OverlayCover(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .fillMaxSize()
            .background(p.ground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
    ) {
        content()
    }
}
