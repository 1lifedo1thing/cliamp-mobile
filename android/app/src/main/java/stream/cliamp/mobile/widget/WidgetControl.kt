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
import stream.cliamp.mobile.data.wrapNext
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
            val t0 = System.currentTimeMillis()
            val token = SessionToken(
                context.applicationContext,
                ComponentName(context.applicationContext, PlaybackService::class.java),
            )
            val controller = MediaController.Builder(context.applicationContext, token)
                .buildAsync()
                .await() ?: return@withContext null
            android.util.Log.d("cliamp/wid", "controller build ms=${System.currentTimeMillis() - t0}")
            try {
                block(controller)
            } finally {
                controller.release()
            }
        }

    suspend fun toggle(context: Context) {
        val app = context.applicationContext as CliampApp
        var target: Boolean? = null
        withController(context) { c ->
            when {
                c.isPlaying -> { c.pause(); target = false }
                c.mediaItemCount > 0 -> { c.prepare(); c.play(); target = true }
                else -> target = null
            }
        }
        if (target == null) {
            // nothing loaded yet: fall back to whatever was on last
            val station = app.prefs.readLastStation()
                ?: app.repository.cliamp.value.firstOrNull()
                ?: CliampRadio.builtin.first()
            tune(context, station)
        } else {
            // Reflect the flip immediately instead of waiting on a second
            // controller round-trip, which is what made stop/resume lag.
            app.prefs.setWidgetPlaying(target == true)
            CliampWidgetReceiver.refresh(context)
        }
    }

    suspend fun step(context: Context, delta: Int) {
        val app = context.applicationContext as CliampApp
        val t0 = System.currentTimeMillis()
        // Walk the persisted window of the current list (local / radio /
        // podcast) rather than the in-memory PlaybackBus, which is empty when
        // the widget wakes a cold process and made prev/next fall back to the
        // built-in radio channels. Falls back to favourites, then cliamp's
        // channels, only when there is no window at all.
        val list = app.prefs.widgetSource.first().ifEmpty {
            PlaybackBus.source.value.ifEmpty {
                app.prefs.favorites.first().ifEmpty { CliampRadio.builtin }
            }
        }
        if (list.isEmpty()) return
        val here = PlaybackBus.station.value?.url ?: app.prefs.readLastStation()?.url
        val i = list.indexOfFirst { it.url == here }
        val target = if (i < 0) list.first() else list[(i + delta + list.size) % list.size]
        android.util.Log.d("cliamp/wid", "step delta=$delta src=${list.size} here=$here target=${target.name} ms=${System.currentTimeMillis() - t0}")
        tune(context, target)
    }

    suspend fun tune(context: Context, station: Station) {
        val app = context.applicationContext as CliampApp
        val t0 = System.currentTimeMillis()
        PlaybackBus.publishStation(station)
        PlaybackBus.publishError(null)
        app.prefs.setLastStation(station)
        app.prefs.pushHistory(station)

        // Reflect the tap immediately: the widget's up-next row and title must
        // follow whatever was just tuned, instead of waiting on the service's
        // next spectrum/state write. Anchored on the persisted window so it
        // works even when the widget wakes a cold process.
        val win = app.prefs.widgetSource.first()
        if (win.isNotEmpty()) {
            val j = win.indexOfFirst { it.url == station.url }
            if (j >= 0) {
                val up = win.wrapNext(j)
                app.prefs.setWidgetNext(up)
            }
        }
        app.prefs.setWidgetTrack(station.meta.orEmpty())

        val resolved = StreamResolver.resolve(station.url)
        android.util.Log.d("cliamp/wid", "tune resolve ms=${System.currentTimeMillis() - t0}")
        withController(context) { c ->
            c.setMediaItem(PlaybackService.mediaItem(context, station, resolved))
            c.prepare()
            c.play()
        }
        android.util.Log.d("cliamp/wid", "tune controller+play ms=${System.currentTimeMillis() - t0}")
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
