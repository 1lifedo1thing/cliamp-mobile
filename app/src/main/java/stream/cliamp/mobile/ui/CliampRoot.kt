package stream.cliamp.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.LocalLibrary
import stream.cliamp.mobile.data.PlaylistStore
import stream.cliamp.mobile.data.Repository
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.playback.PlaybackBus
import stream.cliamp.mobile.playback.PlayerConnection
import stream.cliamp.mobile.ui.components.CliampTabBar
import stream.cliamp.mobile.ui.components.SettingsArm
import stream.cliamp.mobile.ui.components.Tab
import stream.cliamp.mobile.ui.screens.CommandScreen
import stream.cliamp.mobile.ui.screens.LocalScreen
import stream.cliamp.mobile.ui.screens.MiniPlayer
import stream.cliamp.mobile.ui.screens.NowPlayingScreen
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
    dark: Boolean,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.Lib) }
    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }
    // Which Library sub-tab is showing. Lifted here so that closing an overlay
    // that was opened from the providers pane (add wizard or a provider's browse)
    // lands back on providers rather than the library list.
    var libSubTab by remember { mutableStateOf(LibSubTab.Library) }

    val playerState by player.state.collectAsState()
    val station by PlaybackBus.station.collectAsState()
    val streamTitle by PlaybackBus.streamTitle.collectAsState()
    val favorites by prefs.favorites.collectAsState(initial = emptyList())
    val recent by prefs.history.collectAsState(initial = emptyList())
    val reconnect by PlaybackBus.reconnectAttempt.collectAsState()
    val providerAccounts by providers.accounts.collectAsState(initial = emptyList())
    val queue by player.queue.collectAsState(initial = emptyList())

    val onPlay: (Station, List<Station>) -> Unit = { s, from ->
        player.play(s, from)
        repository.reportPlay(s)
    }

    BackHandler(enabled = overlay != Overlay.None || tab != Tab.Lib) {
        when {
            overlay != Overlay.None -> overlay = Overlay.None
            else -> tab = Tab.Lib
        }
    }

    Box(Modifier.fillMaxSize().background(p.ground)) {
        Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (overlay) {
                Overlay.Player -> NowPlayingScreen(
                    repository = repository,
                    prefs = prefs,
                    player = player,
                    onOpenScope = { overlay = Overlay.Scope },
                    onBack = { overlay = Overlay.None },
                )
                Overlay.Queue -> QueueScreen(
                    player = player,
                    current = station,
                    playing = playerState.playing,
                    onPlay = onPlay,
                    onBack = { overlay = Overlay.None },
                )
                Overlay.Scope -> ScopeScreen(
                    prefs = prefs,
                    station = station,
                    streamTitle = streamTitle,
                    playing = playerState.playing,
                    onBack = { overlay = Overlay.None },
                )
                is Overlay.Browse -> {
                    val id = (overlay as Overlay.Browse).accountId
                    val account = providerAccounts.firstOrNull { it.id == id }
                    if (account == null) {
                        overlay = Overlay.None
                    } else {
                        ProviderBrowseScreen(
                            account = account,
                            onBack = { overlay = Overlay.None },
                            onEdit = { overlay = Overlay.Wizard(account.providerKey, account) },
                            onPlay = onPlay,
                            onOpenPlayer = { overlay = Overlay.Player },
                        )
                    }
                }
                is Overlay.Wizard -> {
                    val spec = ProviderCatalog.byKey((overlay as Overlay.Wizard).providerKey)
                    if (spec == null) {
                        overlay = Overlay.None
                    } else {
                        ProviderWizard(
                            spec = spec,
                            existing = (overlay as Overlay.Wizard).account,
                            onCancel = { overlay = Overlay.None },
                            onSave = { account ->
                                scope.launch { providers.save(account) }
                                overlay = Overlay.None
                            },
                        )
                    }
                }
                Overlay.Settings -> SettingsScreen(
                    prefs = prefs,
                    repository = repository,
                    onBack = { overlay = Overlay.None },
                )
                Overlay.None -> when (tab) {
                    Tab.Stations -> StationsScreen(
                        repository = repository,
                        prefs = prefs,
                        current = station,
                        playing = playerState.playing,
                        favorites = favorites,
                        onPlay = onPlay,
                        onToggleFavorite = { s -> scope.launch { prefs.toggleFavorite(s) } },
                    )
                    Tab.Lib -> LocalScreen(
                        localLibrary = localLibrary,
                        playlists = playlists,
                        current = station,
                        playing = playerState.playing,
                        favorites = favorites,
                        recent = recent,
                        onPlay = onPlay,
                        onToggleFavorite = { s -> scope.launch { prefs.toggleFavorite(s) } },
                        onAddToQueue = { player.addToQueue(it) },
                        onOpenPlayer = { overlay = Overlay.Player },
                        providers = providerAccounts,
                        showProviders = libSubTab == LibSubTab.Providers,
                        onShowProviders = { v -> libSubTab = if (v) LibSubTab.Providers else LibSubTab.Library },
                        onOpenProvider = { a ->
                            libSubTab = LibSubTab.Providers
                            overlay = Overlay.Browse(a.id)
                        },
                        onAddProvider = { spec ->
                            libSubTab = LibSubTab.Providers
                            overlay = Overlay.Wizard(spec.key, null)
                        },
                    )
                    Tab.Cmd -> CommandScreen(
                        repository = repository,
                        prefs = prefs,
                        onPlay = onPlay,
                        onOpenScope = { overlay = Overlay.Scope },
                        onOpenSettings = { overlay = Overlay.Settings },
                    )
                }
            }
        }

        // The mini bar never goes away (except while the full player is open):
        // it always shows the current or last-played station, or the empty
        // "nothing playing" state. Queue access lives here, beside the play key.
        if (overlay != Overlay.Player) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                MiniPlayer(
                    station = station ?: recent.firstOrNull(),
                    streamTitle = streamTitle,
                    playing = playerState.playing,
                    buffering = playerState.buffering,
                    reconnecting = reconnect,
                    queueCount = queue.size,
                    onOpenQueue = { overlay = Overlay.Queue },
                    onToggle = { player.toggle() },
                    onOpen = { overlay = Overlay.Player },
                )
            }
        }

        // The player is an overlay, but it is a destination rather than a
        // modal: keeping the menu means you can leave it without a back press.
        if (overlay == Overlay.None || overlay == Overlay.Player) {
            CliampTabBar(
                current = tab,
                onSelect = { tab = it; overlay = Overlay.None },
            )
        }
        }

        if (overlay == Overlay.None) {
            SettingsArm(
                onOpenSettings = { overlay = Overlay.Settings },
            )
        }
    }
}
