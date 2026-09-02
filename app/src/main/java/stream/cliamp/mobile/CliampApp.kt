package stream.cliamp.mobile

import android.app.Application
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.provider.ProviderStore
import stream.cliamp.mobile.data.provider.jellyfin
import stream.cliamp.mobile.data.provider.subsonic
import stream.cliamp.mobile.playback.ResolvedStream
import stream.cliamp.mobile.playback.StreamResolver
import stream.cliamp.mobile.net.Http
import stream.cliamp.mobile.data.LocalLibrary
import stream.cliamp.mobile.data.PlaylistStore
import stream.cliamp.mobile.data.Repository
import stream.cliamp.mobile.playback.PlayerConnection
import stream.cliamp.mobile.widget.CliampWidgetReceiver

/**
 * Manual DI. The graph is four objects deep; a framework would cost more
 * lines than it saves.
 */
@UnstableApi
class CliampApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs: Prefs by lazy { Prefs(this) }
    val providers: ProviderStore by lazy { ProviderStore(this) }
    val repository: Repository by lazy { Repository(prefs, appScope) }
    val localLibrary: LocalLibrary by lazy { LocalLibrary(this) }
    val playlists: PlaylistStore by lazy { PlaylistStore(this) }
    val player: PlayerConnection by lazy { PlayerConnection(this, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }

    override fun onCreate() {
        super.onCreate()
        Http.init(this)

        // Provider stream URLs are signed per request, so they are resolved
        // here at play time rather than stored.
        StreamResolver.providerResolver = resolve@ { accountId, trackId ->
            val account = providers.read().firstOrNull { it.id == accountId } ?: return@resolve null
            if (account.providerKey == "jellyfin" || account.providerKey == "emby") {
                account.jellyfin().stream(trackId)
            } else {
                ResolvedStream(account.subsonic().streamUrl(trackId))
            }
        }
        repository.bootstrap()

        // The last station is restored but never auto-played unless asked:
        // a radio app that starts making noise on launch is a bad neighbour.
        // The widget reads the palette too, and a paused widget gets no
        // nudge from the playback service, so watch the setting directly.
        appScope.launch {
            prefs.palette.distinctUntilChanged().drop(1).collect {
                CliampWidgetReceiver.refresh(this@CliampApp)
            }
        }

        player.onReady = {
            appScope.launch {
                if (prefs.autoResume.first()) {
                    prefs.lastStation.first()?.let { s -> player.play(s) }
                }
            }
        }
    }
}
