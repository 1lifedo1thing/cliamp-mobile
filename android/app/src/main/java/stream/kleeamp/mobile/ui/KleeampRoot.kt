package stream.kleeamp.mobile.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.data.DirectoryQuery
import stream.kleeamp.mobile.data.Prefs
import stream.kleeamp.mobile.data.PodcastRepository
import stream.kleeamp.mobile.data.PodcastShow
import stream.kleeamp.mobile.data.Repository
import stream.kleeamp.mobile.data.Station
import stream.kleeamp.mobile.play.PlayFromList
import stream.kleeamp.mobile.playback.PlaybackBus
import stream.kleeamp.mobile.playback.PlayerConnection
import stream.kleeamp.mobile.chrome.KleeampTabBar
import stream.kleeamp.mobile.chrome.KleeampTabRail
import stream.kleeamp.mobile.ui.components.MiniPlayer
import stream.kleeamp.mobile.chrome.Tab
import stream.kleeamp.mobile.ui.screens.SearchScreen
import stream.kleeamp.mobile.ui.screens.FavScope
import stream.kleeamp.mobile.ui.screens.LibraryPlaylistPane
import stream.kleeamp.mobile.ui.screens.LibraryAddToPlaylistPane
import stream.kleeamp.mobile.ui.screens.LibraryProvidersPane
import stream.kleeamp.mobile.ui.screens.LibrarySmartPlaylistPane
import stream.kleeamp.mobile.ui.screens.LibrarySongInfoPane
import stream.kleeamp.mobile.ui.screens.LocalScreen
import stream.kleeamp.mobile.ui.screens.NowPlayingScreen
import stream.kleeamp.mobile.ui.screens.PodcastShowScreen
import stream.kleeamp.mobile.ui.screens.PodcastsScreen
import stream.kleeamp.mobile.ui.screens.UpNextScreen
import stream.kleeamp.mobile.ui.screens.ScopeScreen
import stream.kleeamp.mobile.ui.screens.ScrobbleWizard as ScrobbleWizardScreen
import stream.kleeamp.mobile.data.provider.ProviderCatalog
import stream.kleeamp.mobile.data.provider.ProviderStore
import stream.kleeamp.mobile.ui.screens.ProviderWizard as ProviderWizardScreen
import stream.kleeamp.mobile.ui.screens.SettingsScreen
import stream.kleeamp.mobile.ui.screens.StationsScreen
import stream.kleeamp.mobile.ui.screens.StationsViewModel
import stream.kleeamp.mobile.ui.screens.PodcastsViewModel
import stream.kleeamp.mobile.ui.screens.PodcastShowViewModel
import stream.kleeamp.mobile.ui.screens.LocalViewModel
import stream.kleeamp.mobile.ui.screens.ProviderSongsPane
import stream.kleeamp.mobile.ui.screens.ProviderSongsViewModel
import stream.kleeamp.mobile.ui.screens.ProvidersPaneViewModel
import stream.kleeamp.mobile.ui.screens.SmartPlaylistViewModel
import stream.kleeamp.mobile.ui.screens.AddToPlaylistViewModel
import stream.kleeamp.mobile.ui.screens.PlaylistDetailViewModel
import stream.kleeamp.mobile.ui.screens.SongInfoViewModel
import stream.kleeamp.mobile.ui.screens.NowPlayingViewModel
import stream.kleeamp.mobile.ui.screens.SearchViewModel
import stream.kleeamp.mobile.ui.screens.SettingsViewModel
import stream.kleeamp.mobile.ui.screens.ProviderWizardViewModel
import stream.kleeamp.mobile.ui.screens.ScrobbleWizardViewModel
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * Forward push: the new page slides in from the right edge while the page
 * beneath scales down slightly - the mirror of the pop return, so going
 * somewhere feels like swiping there.
 */
private val PagePushEnter =
    slideInHorizontally(tween(280)) { it }
private val PagePushExit =
    scaleOut(tween(280), targetScale = 0.92f)

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
fun KleeampRoot(
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

    // The three tabs are pages of one pager, so two of them are only ever a
    // drag apart. The pager's page is the single source of truth for which tab
    // is active: the strip highlight follows the finger across the midpoint of
    // a drag instead of waiting for the page to settle.
    val pagerState = rememberPagerState(pageCount = { Tab.entries.size })
    // False only while a search-widget launch sits on Search with no tab seen
    // yet, so the strip reads as having none selected, the way it always did.
    var tabVisited by rememberSaveable { mutableStateOf(openSearchTick == 0) }
    val tab: Tab? = if (tabVisited) Tab.entries[pagerState.currentPage] else null
    // Search state survives rotation like its neighbours below: a rotate
    // with Search open keeps the query and the pending tag focus.
    var focusDirectory by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // True for a beat after any tab-bar tap: pushes and pops go flat so a
    // switch never flashes the intermediate page's slide+scale. Tab-bar
    // travel is always instant; only a real push or back animates.
    var calmNav by remember { mutableStateOf(false) }
    var calmJob by remember { mutableStateOf<Job?>(null) }
    // The favourites type filter, shared by the library list and the
    // favourites smart detail pane so both agree.
    var favScope by rememberSaveable { mutableStateOf(FavScope.All) }

    // Playback-global state stays at the root: the chrome and every screen
    // read current/playing. Prefs, provider and progress flows are collected
    // down in the chrome/destination that owns them, so a favorite toggle or
    // a provider edit no longer recomposes the whole tree.
    val playerState by player.state.collectAsState()
    val station by PlaybackBus.station.collectAsState()
    val streamTitle by PlaybackBus.streamTitle.collectAsState()
    val reconnect by PlaybackBus.reconnectAttempt.collectAsState()

    // The player's fallback queue follows history without subscribing
    // composition to it: a collect in scope, never a State read in the body.
    LaunchedEffect(Unit) {
        prefs.history.collect { player.setFallbackSource(it) }
    }

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

    val play = PlayFromList { s, from ->
        player.play(s, from)
        repository.reportPlay(s)
    }

    // Move to a tab. Anything sitting above Home - a pane, Settings, Search -
    // is popped first, so a tab tap always lands on the pager and never leaves
    // the strip pointing at a page you cannot see. The calm window flattens
    // that pop: travel by tab tap stays instant, and only a real back animates.
    //
    // Animated for a tab tap, so the strip and a swipe agree on how a tab
    // change looks. Instant when the caller is about to cover the pager, where
    // animating a page the user never sees only costs a frame.
    val goToTab: (Tab, Boolean) -> Unit = { newTab, animate ->
        calmJob?.cancel()
        calmNav = true
        calmJob = scope.launch {
            delay(350)
            calmNav = false
        }
        tabVisited = true
        // Already on the pager: nothing to pop. Asking anyway would report
        // failure and send us down the rebuild path below, which would drop
        // the Home entry and take all three tabs' state with it.
        val onHome = navController.currentDestination?.route
            ?.startsWith(Home::class.qualifiedName!!) == true
        if (!onHome && !navController.popBackStack(Home, false)) {
            // A search-widget launch started on Search, so Home was never on
            // the stack. Replace the stack with it rather than stacking Home
            // on top of a Search the user has already left.
            navController.navigate(Home) {
                popUpTo(navController.graph.startDestinationId) { inclusive = true }
                launchSingleTop = true
            }
        }
        scope.launch {
            if (animate) pagerState.animateScrollToPage(newTab.ordinal)
            else pagerState.scrollToPage(newTab.ordinal)
        }
    }
    val switchTab: (Tab) -> Unit = { newTab -> goToTab(newTab, true) }

    // Route-guarded navigation, read here in the chrome (and once per
    // destination that needs it) so the root never subscribes to the back
    // stack. Guarded opens: tapping the mini player while already on that
    // page is a no-op instead of stacking a duplicate destination.
    val chromeNav = rememberGuardedNav(navController)

    // Cold-start reveal: the first frames rearrange as resident data lands
    // (chrome measures, lists fill), so the whole frame fades and rises in
    // once instead of flashing each change. Saved across rotation so it
    // plays on launch only, never on rotate. Layer properties only: no
    // recomposition rides the animation frames.
    var launched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(50)
        launched = true
    }
    val launchAlpha by animateFloatAsState(
        if (launched) 1f else 0f,
        tween(250),
        label = "launchAlpha",
    )
    val launchRise by animateDpAsState(
        if (launched) 0.dp else 12.dp,
        tween(250),
        label = "launchRise",
    )

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(p.ground)
            .graphicsLayer {
                alpha = launchAlpha
                translationY = launchRise.toPx()
            },
    ) {
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
            ScopedMiniPlayer(
                prefs = prefs,
                player = player,
                station = station,
                streamTitle = streamTitle,
                playing = playerState.playing,
                buffering = playerState.buffering,
                reconnecting = reconnect,
                hasPrev = playerState.hasPrev,
                hasNext = playerState.hasNext,
                onOpenUpNext = chromeNav.openUpNext,
                onOpen = chromeNav.openPlayer,
            )

            if (!rail) {
                KleeampTabBar(
                    current = tab,
                    onSelect = switchTab,
                )
            }
        }
        if (rail) {
            KleeampTabRail(
                current = tab,
                onSelect = switchTab,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    .onSizeChanged { chromeEnd = with(density) { it.width.toDp() } },
            )
        }

        // Single navigation owner: tab roots, tab panes and full overlays all
        // live in this host, so every push slides a page in and every back -
        // overlay or pane - plays the same slide-out + scale-in return,
        // finger-driven on a gesture.
        NavHost(
            navController = navController,
            // A search-widget launch starts directly on Search: no one-frame
            // flash of Stations first. Back from there falls through to the
            // Stations tab (see the Search onBack below).
            startDestination = if (openSearchTick > 0) Search else Home,
            // Transparent: the chrome underneath shows through the padded
            // zone; every overlay paints its own opaque cover instead.
            modifier = Modifier.fillMaxSize(),
            enterTransition = { if (calmNav) EnterTransition.None else PagePushEnter },
            exitTransition = { if (calmNav) ExitTransition.None else PagePushExit },
            popEnterTransition = { if (calmNav) EnterTransition.None else PagePopEnter },
            popExitTransition = { if (calmNav) ExitTransition.None else PagePopExit },
        ) {
            // The three tabs: one destination, three pages. Swiping between
            // them never touches the back stack, so back on any tab has
            // nothing to pop and exits natively, as the start tab always did.
            composable<Home>(
                enterTransition = { rootEnter() },
                exitTransition = { rootExit() },
                popEnterTransition = { if (calmNav) EnterTransition.None else PagePopEnter },
                popExitTransition = { ExitTransition.None },
            ) {
                // Landing here marks a tab as seen: a search-widget launch
                // that reaches Home shows a selected tab from now on.
                LaunchedEffect(Unit) { tabVisited = true }
                HorizontalPager(
                    state = pagerState,
                    // Bounded to the content zone, not the whole frame, so the
                    // pager ends above the mini player and tab strip. The
                    // chrome keeps the touch geometry it always had: a drag
                    // across the mini player still does nothing.
                    modifier = contentModifier,
                    key = { it },
                    // Keep the neighbouring tab built. At the first pixel of a
                    // drag the page beside you is already composed, so a swipe
                    // uncovers a finished page instead of building one mid-drag.
                    // Three pages is the whole set, so the cost is bounded.
                    beyondViewportPageCount = 1,
                ) { page ->
                    // Each tab keeps its own chip rows, which scroll
                    // horizontally as well. They consume the drag while they
                    // still have room and hand it to the pager at their edge,
                    // so a chip row slows a swipe but never swallows it.
                    Box(Modifier.fillMaxSize()) {
                        when (Tab.entries[page]) {
                            Tab.Stations -> StationsTab(
                                prefs = prefs,
                                current = station,
                                playing = playerState.playing,
                                onPlay = { s, from -> play(s, from) },
                                onAddToQueue = { player.addToUpNext(it) },
                                onOpenSearch = {
                                    navController.navigate(Search)
                                },
                                onOpenSettings = {
                                    navController.navigate(Settings)
                                },
                                focusDirectory = focusDirectory,
                                onDirectoryFocusConsumed = { focusDirectory = false },
                            )
                            Tab.Pods -> PodcastsScreen(
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
                            Tab.Lib -> LocalScreen(
                                vm = appViewModel { app ->
                                    LocalViewModel(app.localLibrary, app.playlists, app.prefs, app.providers)
                                },
                                onOpenProviderSongs = { navController.navigate(LibraryProviders) },
                                onOpenSmart = { kind -> navController.navigate(LibrarySmartPlaylist(kind)) },
                                onOpenPlaylist = { slug -> navController.navigate(LibraryPlaylist(slug)) },
                                onPickSongs = { slug ->
                                    navController.navigate(LibraryPlaylist(slug, pickSongs = true))
                                },
                                onOpenSearch = { navController.navigate(Search) },
                                onOpenSettings = { navController.navigate(Settings) },
                                favScope = favScope,
                                visible = pagerState.currentPage == Tab.Lib.ordinal,
                            )
                        }
                    }
                }
            }

            // -- Tab panes (sit above the pager, keep the chrome) --
            composable<PodcastShowRoute> {
                Box(contentModifier) {
                    PodcastShowScreen(
                        vm = appViewModel { app ->
                            PodcastShowViewModel(app.podcasts, app.prefs, app.downloads)
                        },
                        current = station,
                        playing = playerState.playing,
                        onBack = { navController.popBackStack() },
                        onPlay = { s, from -> play(s, from) },
                        onAddToUpNext = { player.addToUpNext(it) },
                        onPlayNext = { player.playNext(it) },
                        onOpenSearch = { navController.navigate(Search) },
                        onOpenSettings = { navController.navigate(Settings) },
                    )
                }
            }
            composable<LibraryProviders> {
                Box(contentModifier) {
                    LibraryProvidersPane(
                        vm = appViewModel { app -> ProvidersPaneViewModel(app.providers) },
                        onBack = { navController.popBackStack() },
                        onOpenProvider = { a -> navController.navigate(LibraryProviderSongs(a.id)) },
                        onEditProvider = { account ->
                            navController.navigate(
                                ProviderWizardRoute(account.providerKey, account.id)
                            )
                        },
                        onAddProvider = { spec ->
                            navController.navigate(ProviderWizardRoute(spec.key))
                        },
                        onOpenSearch = { navController.navigate(Search) },
                        onOpenSettings = { navController.navigate(Settings) },
                    )
                }
            }
            composable<LibraryProviderSongs> { entry ->
                val songsAccountId = entry.toRoute<LibraryProviderSongs>().accountId
                Box(contentModifier) {
                    ProviderSongsPane(
                        vm = appViewModel { app ->
                            ProviderSongsViewModel(app.providers, app.prefs)
                        },
                        accountId = songsAccountId,
                        current = station,
                        playing = playerState.playing,
                        onPlay = { s, from -> play(s, from) },
                        onAddToQueue = { player.addToUpNext(it) },
                        onBack = { navController.popBackStack() },
                        onOpenSearch = { navController.navigate(Search) },
                        onOpenSettings = { navController.navigate(Settings) },
                    )
                }
            }
            composable<LibrarySmartPlaylist> { entry ->
                val kind = entry.toRoute<LibrarySmartPlaylist>().kind
                val progress by podcasts.progress.collectAsState(initial = emptyMap())
                Box(contentModifier) {
                    LibrarySmartPlaylistPane(
                        vm = appViewModel(key = kind) { app ->
                            SmartPlaylistViewModel(kind, app.localLibrary, app.prefs, app.downloads, app.repository, app.podcasts)
                        },
                        kindName = kind,
                        current = station,
                        playing = playerState.playing,
                        onPlay = { s, from -> play(s, from) },
                        onAddToQueue = { player.addToUpNext(it) },
                        favScope = favScope,
                        onFavScopeChange = { favScope = it },
                        onOpenSongInfo = { s -> navController.navigate(LibrarySongInfo(s.url)) },
                        onBack = { navController.popBackStack() },
                        onOpenSearch = { navController.navigate(Search) },
                        onOpenSettings = { navController.navigate(Settings) },
                        progress = progress,
                        onAddToPlaylist = { s -> navController.navigate(LibraryAddToPlaylist(s.url)) },
                    )
                }
            }
            composable<LibraryPlaylist> { entry ->
                val route = entry.toRoute<LibraryPlaylist>()
                val slug = route.slug
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
                        onPlay = { s, from -> play(s, from) },
                        onAddToQueue = { player.addToUpNext(it) },
                        onBack = { navController.popBackStack() },
                        onOpenSearch = { navController.navigate(Search) },
                        onOpenSettings = { navController.navigate(Settings) },
                        onAddToPlaylist = { s -> navController.navigate(LibraryAddToPlaylist(s.url)) },
                        startAdding = route.pickSongs,
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
            composable<LibraryAddToPlaylist> { entry ->
                val stationUrl = entry.toRoute<LibraryAddToPlaylist>().stationUrl
                Box(contentModifier) {
                    LibraryAddToPlaylistPane(
                        vm = appViewModel(key = "add:$stationUrl") { app ->
                            AddToPlaylistViewModel(
                                stationUrl,
                                app.localLibrary,
                                app.playlists,
                                app.prefs,
                                app.repository,
                            )
                        },
                        onBack = { navController.popBackStack() },
                        onOpenSearch = { navController.navigate(Search) },
                        onOpenSettings = { navController.navigate(Settings) },
                    )
                }
            }

            // -- Full overlay destinations (cover the chrome) --
            composable<Player> {
                OverlayCover {
                NowPlayingScreen(
                    vm = appViewModel { app -> NowPlayingViewModel(app.player, app.prefs) },
                    onOpenScope = { navController.navigate(Scope) },
                    onOpenUpNext = rememberGuardedNav(navController).openUpNext,
                    onBack = { navController.popBackStack() },
                )
                }
            }
            composable<UpNext> {
                OverlayCover {
                UpNextScreen(
                    player = player,
                    current = station,
                    playing = playerState.playing,
                    onPlay = { index ->
                        player.currentUpNext.getOrNull(index)?.let(repository::reportPlay)
                        player.playUpNextEntry(index)
                    },
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
                    onPlay = { s, from -> play(s, from) },
                    onOpenProvider = { account -> navController.navigate(LibraryProviderSongs(account.id)) },
                    onOpenShow = { show: PodcastShow ->
                        podcasts.openShow(show)
                        // Pop the search overlay, switch to podcasts tab,
                        // and navigate to the show.
                        navController.popBackStack()
                        goToTab(Tab.Pods, false)
                        navController.navigate(PodcastShowRoute(show.id))
                    },
                    onOpenTag = { name ->
                        repository.loadDirectory(DirectoryQuery.Tag(name), reset = true)
                        focusDirectory = true
                        navController.popBackStack()
                        goToTab(Tab.Stations, false)
                    },
                    onBack = {
                        // Normally pops back to the tab underneath. When a
                        // search-widget launch started directly on Search
                        // there is nothing to pop: fall through to Stations
                        // and mark it visited so it reads as selected.
                        if (!navController.popBackStack()) {
                            goToTab(Tab.Stations, false)
                        }
                    },
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                )
                }
            }
            composable<ProviderWizardRoute> { entry ->
                val route = entry.toRoute<ProviderWizardRoute>()
                val spec = ProviderCatalog.byKey(route.providerKey)
                if (spec == null) {
                    // Same as above: navigate only from an effect, never from
                    // the composable body.
                    LaunchedEffect(route.providerKey) {
                        navController.popBackStack()
                    }
                } else {
                    val providerAccounts by providers.accounts.collectAsState(initial = null)
                    val existing = if (route.accountId.isNotEmpty()) {
                        providerAccounts?.firstOrNull { it.id == route.accountId }
                    } else null
                    // Editing waits for the list: a first-frame null is
                    // "loading", not "new" - building the form on it leaves
                    // every field empty, and the blank form survives because
                    // the viewmodel is keyed, not recreated. A loaded list
                    // without the id is a deleted account: pop out.
                    if (route.accountId.isNotEmpty() && providerAccounts != null && existing == null) {
                        LaunchedEffect(route.accountId) {
                            navController.popBackStack()
                        }
                    } else if (providerAccounts == null && route.accountId.isNotEmpty()) {
                        // Hold the previous page until accounts arrive.
                    } else {
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
private fun OverlayCover(content: @Composable () -> Unit) {    val p = LocalPalette.current
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

/**
 * Route-guarded opens, scoped to the caller: the back-stack entry is read
 * here, so a navigation only recomposes the chrome or the destination that
 * asked - never the root.
 */
private class GuardedNav(
    val openPlayer: () -> Unit,
    val openUpNext: () -> Unit,
)

@Composable
private fun rememberGuardedNav(navController: NavHostController): GuardedNav {
    val route = navController.currentBackStackEntryAsState().value?.destination?.route
    return remember(route) {
        GuardedNav(
            openPlayer = {
                if (route?.startsWith(Player::class.qualifiedName!!) != true) {
                    navController.navigate(Player)
                }
            },
            openUpNext = {
                if (route?.startsWith(UpNext::class.qualifiedName!!) != true) {
                    navController.navigate(UpNext)
                }
            },
        )
    }
}

/**
 * Mini player with its history collected locally: the fallback station
 * lives here, so it never recomposes the root when it changes. The bar
 * shows covers, not the meter, so the visualizer setting is not read here.
 */
@Composable
private fun ScopedMiniPlayer(
    prefs: Prefs,
    player: PlayerConnection,
    station: Station?,
    streamTitle: String,
    playing: Boolean,
    buffering: Boolean,
    reconnecting: Int,
    hasPrev: Boolean,
    hasNext: Boolean,
    onOpenUpNext: () -> Unit,
    onOpen: () -> Unit,
) {
    val recent by prefs.history.collectAsState(initial = emptyList())
    MiniPlayer(
        station = station ?: recent.firstOrNull(),
        streamTitle = streamTitle,
        playing = playing,
        buffering = buffering,
        reconnecting = reconnecting,
        hasPrev = hasPrev,
        hasNext = hasNext,
        onPrev = { player.prev() },
        onNext = { player.next() },
        onOpenUpNext = onOpenUpNext,
        onToggle = { player.toggle(station ?: recent.firstOrNull()) },
        onOpen = onOpen,
    )
}

/**
 * Stations tab with its favorites collected locally: toggling a star only
 * recomposes this tab, not the pager, the chrome or the other tabs.
 */
@Composable
private fun StationsTab(
    prefs: Prefs,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onAddToQueue: (Station) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    focusDirectory: Boolean,
    onDirectoryFocusConsumed: () -> Unit,
) {
    val favorites by prefs.favorites.collectAsState(initial = emptyList())
    StationsScreen(
        vm = appViewModel { app -> StationsViewModel(app.repository, app.prefs) },
        current = current,
        playing = playing,
        favorites = favorites,
        onPlay = onPlay,
        onAddToQueue = onAddToQueue,
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        focusDirectory = focusDirectory,
        onDirectoryFocusConsumed = onDirectoryFocusConsumed,
    )
}
