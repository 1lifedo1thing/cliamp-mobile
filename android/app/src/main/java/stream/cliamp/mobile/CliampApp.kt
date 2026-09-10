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
import stream.cliamp.mobile.data.provider.SftpLibrary
import stream.cliamp.mobile.data.provider.audiobookshelf
import stream.cliamp.mobile.data.provider.jellyfin
import stream.cliamp.mobile.data.provider.plex
import stream.cliamp.mobile.data.provider.subsonic
import stream.cliamp.mobile.playback.ResolvedStream
import stream.cliamp.mobile.playback.SftpDataSource
import stream.cliamp.mobile.playback.StreamResolver
import stream.cliamp.mobile.net.Http
import stream.cliamp.mobile.data.LocalLibrary
import stream.cliamp.mobile.data.StationArtSource
import stream.cliamp.mobile.data.PlaylistStore
import stream.cliamp.mobile.data.PodcastRepository
import stream.cliamp.mobile.data.RadioBrowser
import stream.cliamp.mobile.data.Repository
import stream.cliamp.mobile.playback.PlayerConnection
import stream.cliamp.mobile.widget.WidgetRenderer

/**
 * Manual DI. The graph is four objects deep; a framework would cost more
 * lines than it saves.
 */
@UnstableApi
class CliampApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs: Prefs by lazy { Prefs(this) }
    val providers: ProviderStore by lazy { ProviderStore(this) }
    val repository: Repository by lazy { Repository(this, prefs, appScope) }
    val podcasts: PodcastRepository by lazy { PodcastRepository(this, appScope) }
    val localLibrary: LocalLibrary by lazy { LocalLibrary(this) }
    val playlists: PlaylistStore by lazy { PlaylistStore(this) }
    val player: PlayerConnection by lazy { PlayerConnection(this, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }

    override fun onCreate() {
        super.onCreate()
        Http.init(this)
        RadioBrowser.init(this)

        StationArtSource.init(this)
        SftpLibrary.init(this, providers, appScope)

        // Provider stream URLs are signed per request, so they are resolved
        // here at play time rather than stored.
        StreamResolver.providerResolver = resolve@ { accountId, trackId ->
            val account = providers.read().firstOrNull { it.id == accountId } ?: return@resolve null
            when (account.providerKey) {
                // Not an HTTP URL at all: the track id is the remote path, and
                // the data source opens it over the account's SSH connection.
                "ssh" -> ResolvedStream(SftpDataSource.uriFor(account.id, trackId))
                "jellyfin", "emby" -> account.jellyfin().stream(trackId)
                "plex" -> account.plex().stream(trackId)
                "abs" -> account.audiobookshelf().stream(trackId)
                else -> ResolvedStream(account.subsonic().streamUrl(trackId))
            }
        }
        // Episode positions are the one thing the player cannot work out for
        // itself, and the one thing a podcast is useless without. Wired here
        // for the same reason the provider resolver is: playback should not be
        // holding a database.
        player.resumeLookup = { station -> podcasts.resumePosition(station) }
        player.progressSink = { station, position, duration ->
            podcasts.saveProgress(station, position, duration)
        }

        repository.bootstrap()
        podcasts.bootstrap()

        // The last station is restored but never auto-played unless asked:
        // a radio app that starts making noise on launch is a bad neighbour.
        // The widget reads the palette too, and a paused widget gets no
        // nudge from the playback service, so watch the setting directly.
        appScope.launch {
            prefs.palette.distinctUntilChanged().drop(1).collect {
                WidgetRenderer.refresh(this@CliampApp)
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
