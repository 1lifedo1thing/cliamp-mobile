package stream.cliamp.mobile.widget

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import stream.cliamp.mobile.CliampApp
import stream.cliamp.mobile.data.CliampRadio
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.playback.PlaybackBus
import stream.cliamp.mobile.playback.PlaybackService
import stream.cliamp.mobile.playback.StreamResolver
import kotlin.coroutines.resume

/**
 * Widget and tile taps go through a MediaController, not through
 * startForegroundService.
 *
 * The intent route looks cheaper but is a trap: starting a service that way
 * gives it five seconds to call startForeground, and tuning a station has to
 * read preferences and follow a playlist redirect first. On a slow connection
 * that window closes and the system kills the app. Connecting a controller
 * binds the service instead, and Media3 promotes it to the foreground itself
 * once playback actually begins.
 */
@UnstableApi
object WidgetControl {

    private suspend fun <T> ListenableFuture<T>.await(): T? =
        suspendCancellableCoroutine { cont ->
            addListener({ cont.resume(runCatching { get() }.getOrNull()) }, MoreExecutors.directExecutor())
            cont.invokeOnCancellation { cancel(false) }
        }

    private suspend fun <T> withController(context: Context, block: (MediaController) -> T): T? =
        withContext(Dispatchers.Main) {
            val token = SessionToken(
                context.applicationContext,
                ComponentName(context.applicationContext, PlaybackService::class.java),
            )
            val controller = MediaController.Builder(context.applicationContext, token)
                .buildAsync()
                .await() ?: return@withContext null
            try {
                block(controller)
            } finally {
                controller.release()
            }
        }

    suspend fun toggle(context: Context) {
        val app = context.applicationContext as CliampApp
        val started = withController(context) { c ->
            when {
                c.isPlaying -> { c.pause(); true }
                c.mediaItemCount > 0 -> { c.prepare(); c.play(); true }
                else -> false
            }
        }
        if (started != true) {
            // nothing loaded yet: fall back to whatever was on last
            val station = app.prefs.readLastStation()
                ?: app.repository.cliamp.value.firstOrNull()
                ?: CliampRadio.builtin.first()
            tune(context, station)
        }
        publish(context)
    }

    suspend fun step(context: Context, delta: Int) {
        val app = context.applicationContext as CliampApp
        // Walk the list currently playing (local songs, favourites, a provider
        // album, a directory); fall back to favourites, then cliamp's channels.
        val list = PlaybackBus.source.value.ifEmpty {
            app.prefs.favorites.first().ifEmpty { CliampRadio.builtin }
        }
        if (list.isEmpty()) return
        val here = PlaybackBus.station.value?.url ?: app.prefs.readLastStation()?.url
        val i = list.indexOfFirst { it.url == here }
        tune(context, if (i < 0) list.first() else list[(i + delta + list.size) % list.size])
    }

    suspend fun tune(context: Context, station: Station) {
        val app = context.applicationContext as CliampApp
        PlaybackBus.publishStation(station)
        PlaybackBus.publishError(null)
        app.prefs.setLastStation(station)
        app.prefs.pushHistory(station)

        val resolved = StreamResolver.resolve(station.url)
        withController(context) { c ->
            c.setMediaItem(PlaybackService.mediaItem(context, station, resolved))
            c.prepare()
            c.play()
        }
        publish(context)
    }

    private suspend fun publish(context: Context) {
        val app = context.applicationContext as CliampApp
        val playing = withController(context) { it.isPlaying } ?: false
        app.prefs.setWidgetPlaying(playing)
        CliampWidgetReceiver.refresh(context)
    }

    @Suppress("unused")
    private fun unusedPlayerRef(): Class<Player> = Player::class.java
}
