package stream.kleeamp.mobile

import android.app.Application
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.data.Prefs
import stream.kleeamp.mobile.data.provider.ProviderStore
import stream.kleeamp.mobile.data.provider.SftpLibrary
import stream.kleeamp.mobile.data.provider.audiobookshelf
import stream.kleeamp.mobile.data.provider.jellyfin
import stream.kleeamp.mobile.data.provider.lyrion
import stream.kleeamp.mobile.data.provider.plex
import stream.kleeamp.mobile.data.provider.subsonic
import stream.kleeamp.mobile.playback.ResolvedStream
import stream.kleeamp.mobile.playback.SftpDataSource
import stream.kleeamp.mobile.playback.StreamResolver
import stream.kleeamp.mobile.net.Http
import stream.kleeamp.mobile.data.DownloadStore
import stream.kleeamp.mobile.data.LocalLibrary
import stream.kleeamp.mobile.data.StationArtSource
import stream.kleeamp.mobile.data.PlaylistStore
import stream.kleeamp.mobile.data.PodcastRepository
import stream.kleeamp.mobile.data.RadioBrowser
import stream.kleeamp.mobile.data.Repository
import stream.kleeamp.mobile.data.Scrobbler
import stream.kleeamp.mobile.playback.PlayerConnection
import stream.kleeamp.mobile.widget.WidgetRenderer

/**
 * Manual DI. The graph is four objects deep; a framework would cost more
 * lines than it saves.
 */
@UnstableApi
class KleeampApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs: Prefs by lazy { Prefs(this) }
    val providers: ProviderStore by lazy { ProviderStore(this) }
    val repository: Repository by lazy { Repository(this, prefs, appScope) }
    val podcasts: PodcastRepository by lazy { PodcastRepository(this, appScope) }
    val localLibrary: LocalLibrary by lazy { LocalLibrary(this, appScope) }
    val playlists: PlaylistStore by lazy { PlaylistStore(this) }
    val player: PlayerConnection by lazy { PlayerConnection(this, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
    val downloads: DownloadStore by lazy { DownloadStore(this, prefs, appScope) }
    val scrobbler: Scrobbler by lazy { Scrobbler(this, prefs, appScope) }

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
                "lyrion" -> account.lyrion().stream(trackId)
                else -> ResolvedStream(account.subsonic().streamUrl(trackId))
            }
        }
        // Episode positions are the one thing the player cannot work out for
        // itself, and the one thing a podcast is useless without. Wired here
        // for the same reason the provider resolver is: playback should not be
        // holding a database.
        StreamResolver.downloadLookup = { url -> downloads.localPath(url) }
        player.scrobbleTick = { station, playing, durationMs ->
            scrobbler.onTick(station, playing, durationMs)
        }
        player.resumeLookup = { station -> podcasts.resumePosition(station) }
        player.progressSink = { station, position, duration ->
            podcasts.saveProgress(station, position, duration)
        }

        repository.bootstrap()
        podcasts.bootstrap()

        // The widget reads the palette and the visualizer family too, and a
        // paused widget gets no nudge from the playback service, so watch
        // both settings directly: a theme or visualizer change re-renders
        // even with nothing playing. Merged, not combined - combine would
        // wait for both to emit, so toggling one alone would never fire.
        appScope.launch {
            merge(
                prefs.palette.distinctUntilChanged().drop(1),
                prefs.visualizer.distinctUntilChanged().drop(1),
            ).collect {
                WidgetRenderer.refresh(this@KleeampApp)
            }
        }

        player.onReady = {
            // The last station is restored but never auto-played unless
            // asked: a radio app that starts making noise on launch is a bad
            // neighbour.
            appScope.launch {
                if (prefs.autoResume.first()) {
                    prefs.lastStation.first()?.let { s -> player.play(s) }
                }
            }
        }
    }
}
