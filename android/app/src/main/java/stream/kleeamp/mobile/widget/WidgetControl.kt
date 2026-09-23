package stream.kleeamp.mobile.widget

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
import stream.kleeamp.mobile.KleeampApp
import stream.kleeamp.mobile.data.CliampRadio
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.wrapNext
import stream.kleeamp.mobile.playback.PlaybackBus
import stream.kleeamp.mobile.playback.PlaybackService
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
            android.util.Log.d("kleeamp/wid", "controller build ms=${System.currentTimeMillis() - t0}")
            try {
                block(controller)
            } finally {
                controller.release()
            }
        }

    suspend fun toggle(context: Context) {
        val app = context.applicationContext as KleeampApp
        var target: Boolean? = null
        withController(context) { c ->
            // playWhenReady is the tap intent; isPlaying is audibility. During
            // buffering isPlaying is false while intent is still "playing", so
            // checking isPlaying turns a pause-tap mid-stall into a second
            // play() and leaves the glyph stuck.
            val intendingToPlay = c.playWhenReady && c.mediaItemCount > 0
            when {
                intendingToPlay -> { c.pause(); target = false }
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
            // Push the flip straight to the widget with the known intent;
            // the DataStore write beside it is persistence only, nothing on
            // screen waits for it. The service confirms via its own events.
            val playing = target == true
            app.prefs.setWidgetPlaying(playing)
            WidgetRenderer.push(
                context,
                PlaybackBus.station.value ?: app.prefs.readLastStation(),
                PlaybackBus.streamTitle.value.ifBlank { app.prefs.widgetTrack.first() },
                playing,
            )
        }
    }

    suspend fun step(context: Context, delta: Int) {
        val app = context.applicationContext as KleeampApp
        // A running session owns occurrence indices and the edited queue.
        // Re-finding a URL in the persisted ring loses duplicate occurrences.
        val handled = withContext(Dispatchers.Main) {
            if (app.player.currentUpNext.isEmpty()) false else {
                if (delta < 0) app.player.prev() else app.player.next()
                true
            }
        }
        if (handled) return
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
        android.util.Log.d("kleeamp/wid", "step delta=$delta src=${list.size} here=$here target=${target.name} ms=${System.currentTimeMillis() - t0}")
        tune(context, target)
    }

    suspend fun tune(context: Context, station: Station) {
        val app = context.applicationContext as KleeampApp
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
        // Optimistic intent: resolve() follows playlist redirects over the
        // network, so without this the glyph sits on paused through the whole
        // tune. publish() after play() confirms it; the service is the final
        // writer via onPlayWhenReadyChanged.
        app.prefs.setWidgetPlaying(true)
        WidgetRenderer.push(
            context, station, station.meta.orEmpty(), true,
            seekable = station.isTrack,
            durationMs = station.durationMs,
            positionMs = 0L,
        )

        val resolved = app.streamResolver.resolve(station.url)
        android.util.Log.d("kleeamp/wid", "tune resolve ms=${System.currentTimeMillis() - t0}")
        withController(context) { c ->
            c.setMediaItem(PlaybackService.mediaItem(context, station, resolved))
            c.prepare()
            c.play()
        }
        android.util.Log.d("kleeamp/wid", "tune controller+play ms=${System.currentTimeMillis() - t0}")
        publish(context, station)
    }

    private suspend fun publish(context: Context, station: Station) {
        val app = context.applicationContext as KleeampApp
        // Read intent, not audibility: right after play() the player is still
        // BUFFERING so isPlaying is false and the widget would be parked on
        // the play glyph until first audio. playWhenReady is true from the tap.
        val playing = withController(context) { it.playWhenReady && it.mediaItemCount > 0 } ?: false
        app.prefs.setWidgetPlaying(playing)
        WidgetRenderer.push(
            context, station, station.meta.orEmpty(), playing,
            seekable = station.isTrack,
            durationMs = station.durationMs,
        )
    }

    @Suppress("unused")
    private fun unusedPlayerRef(): Class<Player> = Player::class.java
}
