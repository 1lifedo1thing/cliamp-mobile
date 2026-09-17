package stream.kleeamp.mobile.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps a live stream alive across the two ways radio actually dies.
 *
 *  1. A hard error. The socket drops, the server 502s, DNS goes away.
 *     ExoPlayer raises onPlayerError and parks in STATE_IDLE forever, because
 *     from its point of view the media is gone. Nothing retries on its own.
 *
 *  2. A silent stall. The connection stays open but stops delivering, so the
 *     player sits in STATE_BUFFERING indefinitely with no error at all. This
 *     is the common one on a train, and it is invisible to error handling.
 *
 * Both are handled here: backoff for the first, a watchdog for the second, and
 * a network callback so coming back into signal retries immediately instead of
 * waiting out the backoff.
 */
@UnstableApi
class Reconnector(
    private val context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
    private val onState: (Boolean, Int) -> Unit,
) {
    /** 1s, 2s, 4s, 8s, 15s, then every 30s. Radio outages are often minutes. */
    private val backoff = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
    private val stallTimeoutMs = 20_000L

    private var attempt = 0
    private var retryJob: Job? = null
    private var watchdogJob: Job? = null
    private var bufferingSince = 0L
    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    val isRetrying: Boolean get() = retryJob?.isActive == true

    fun attach() {
        player.addListener(Listener())
        watchdogJob = scope.launch {
            while (true) {
                delay(2_000)
                checkStall()
            }
        }
        registerNetworkCallback()
    }

    fun detach() {
        retryJob?.cancel()
        watchdogJob?.cancel()
        networkCallback?.let { cb -> runCatching { connectivity?.unregisterNetworkCallback(cb) } }
        networkCallback = null
    }

    private inner class Listener : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (!player.playWhenReady) return
            if (!recoverable(error)) {
                Log.w(TAG, "not retrying: ${error.errorCodeName}")
                return
            }
            scheduleRetry("error ${error.errorCodeName}")
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    // a stream that actually delivers audio resets the ladder
                    attempt = 0
                    bufferingSince = 0
                    retryJob?.cancel()
                    onState(false, 0)
                }
                Player.STATE_BUFFERING ->
                    if (bufferingSince == 0L) bufferingSince = System.currentTimeMillis()
                else -> bufferingSince = 0
            }
        }
    }

    /**
     * Everything except a malformed container or an unsupported codec is worth
     * another go. Retrying a stream the decoder cannot play would just spin.
     */
    private fun recoverable(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        -> false
        else -> true
    }

    private fun checkStall() {
        if (!player.playWhenReady || bufferingSince == 0L) return
        if (player.playbackState != Player.STATE_BUFFERING) return
        if (System.currentTimeMillis() - bufferingSince < stallTimeoutMs) return
        bufferingSince = 0
        scheduleRetry("stalled for ${stallTimeoutMs / 1000}s with no error")
    }

    private fun scheduleRetry(reason: String) {
        if (retryJob?.isActive == true) return
        val wait = backoff[attempt.coerceAtMost(backoff.lastIndex)]
        attempt++
        onState(true, attempt)
        Log.i(TAG, "reconnect #$attempt in ${wait}ms ($reason)")
        retryJob = scope.launch {
            delay(wait)
            if (!player.playWhenReady) {
                onState(false, 0)
                return@launch
            }
            player.prepare()
            player.play()
        }
    }

    /** Coming back into signal should retry now, not after the remaining backoff. */
    private fun registerNetworkCallback() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        connectivity = cm
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // This fires on ConnectivityThread. ExoPlayer permits access
                // only from its application thread, so every read of player
                // state has to happen inside the launch, not around it.
                scope.launch {
                    if (!player.playWhenReady) return@launch
                    if (player.playbackState != Player.STATE_IDLE && !isRetrying) return@launch
                    retryJob?.cancel()
                    attempt = 0
                    onState(true, 1)
                    player.prepare()
                    player.play()
                }
            }
        }
        networkCallback = cb
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb,
            )
        }
    }

    private companion object { const val TAG = "kleeamp/reconnect" }
}
