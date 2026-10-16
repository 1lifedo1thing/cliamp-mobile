package stream.kleeamp.mobile.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** How long a track change hides buffering/reconnecting. Most slow streams join inside this. */
const val NAV_GRACE_MS = 3_000L

/**
 * True while a navigation is still fresh. Pure so unit tests can pin the
 * boundary without a clock.
 */
fun isWithinGrace(nowMs: Long, lastNavMs: Long, graceMs: Long = NAV_GRACE_MS): Boolean =
    lastNavMs > 0L && nowMs - lastNavMs < graceMs

/** Keep the pause glyph during grace when playback is intended. */
fun effectivePlaying(playing: Boolean, wantPlaying: Boolean, inGrace: Boolean): Boolean =
    playing || (wantPlaying && inGrace)

/** Hide the buffering label/dot during grace. */
fun effectiveBuffering(buffering: Boolean, inGrace: Boolean): Boolean =
    buffering && !inGrace

/** Hide the reconnect counter during grace; long outages surface after. */
fun effectiveReconnect(reconnect: Int, inGrace: Boolean): Int =
    if (inGrace) 0 else reconnect

/**
 * True for [graceMs] after [key] changes. Each next/prev/tap gets a fresh
 * window; a pause inside the window is handled by the caller combining this
 * with wantPlaying, so pausing never reads as playing.
 */
@Composable
fun rememberNavGrace(key: String?, graceMs: Long = NAV_GRACE_MS): Boolean {
    var inGrace by remember(key) { mutableStateOf(key != null) }
    LaunchedEffect(key) {
        if (key == null) {
            inGrace = false
        } else {
            inGrace = true
            delay(graceMs)
            inGrace = false
        }
    }
    return inGrace
}
