package stream.kleeamp.mobile.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.player.vis.StereoMetrics
import stream.kleeamp.mobile.player.vis.Visualizer
import stream.kleeamp.mobile.playback.AudioOutput
import stream.kleeamp.mobile.playback.AudioOutputs
import stream.kleeamp.mobile.playback.OutputKind
import stream.kleeamp.mobile.playback.PlaybackBus
import stream.kleeamp.mobile.playback.PlayerState
import stream.kleeamp.mobile.chrome.clock
import stream.kleeamp.mobile.chrome.compact
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.MechKey
import stream.kleeamp.mobile.chrome.MeterSize
import stream.kleeamp.mobile.chrome.MarqueeLabel
import stream.kleeamp.mobile.chrome.Scrubber
import stream.kleeamp.mobile.chrome.StreamingRule
import stream.kleeamp.mobile.chrome.ArtGlow
import stream.kleeamp.mobile.chrome.ArtPlate
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.chrome.rememberAudioOutputs
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono
import stream.kleeamp.mobile.art.LocalArt
import stream.kleeamp.mobile.model.NowPlaying


/** The meter/scrubber zone plus its time readout, shared by both layouts. */
@Composable
internal fun PlayerTransport(
    model: PlayerModel,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
    // Landscape passes a shorter meter: the short frame cannot fit the
    // full portrait height above the transport keys.
    meterHeight: androidx.compose.ui.unit.Dp = MeterSize.NowPlaying.height,
) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // The meter is the visualizer: when the setting is off it is
        // removed entirely, not just fed idle data - so neither the
        // frame loop nor a static brick grid exists in the player.
        // A horizontal swipe pages through the visualizer list: left for
        // the next one, right for the previous, wrapping at the ends. The
        // write goes to the global setting, so every meter everywhere -
        // player, scope sheet, fullscreen - follows the swipe. The drag
        // follows the finger and stays cancelable, like the cover pager.
        if (model.visualizer != "off") {
            val order = remember { Visualizer.selectable }
            val mode = Visualizer.byId(model.visualizer)
            VisSkip(
                current = mode,
                previous = order.prevBefore(mode),
                next = order.nextAfter(mode),
                onSelectPrevious = { actions.onSelectVisualizer(order.prevBefore(mode).id) },
                onSelectNext = { actions.onSelectVisualizer(order.nextAfter(mode).id) },
                columns = MeterSize.NowPlaying.columns,
                live = model.state.playing,
                spectrumProvider = model.spectrumProvider,
                stereoProvider = model.stereoProvider,
                brick = MeterSize.NowPlaying.brick,
                gap = MeterSize.NowPlaying.gap,
                generation = model.generation,
                onDoubleTap = actions.onToggleFullscreen,
                modifier = Modifier.fillMaxWidth().height(meterHeight),
            )
        }

        // Tracks always scrub: local files, podcasts, provider tracks
        // and cliamp channel tracks. Live radio keeps the streaming rule.
        // The bar stays up during reconnecting or stream error and while
        // the duration is still unknown, so a track never flashes a
        // "playing" label instead; seeks then no-op until seekable.
        if (model.shownStation?.isTrack == true) {
            // Touchable even before load: a touch primes the position and
            // the track loads from there once the duration lands. Only a
            // hard error disables it; reconnecting still accepts the prime.
            val seekEnabled = model.error == null
            val fraction = if (model.state.durationMs > 0) {
                model.state.positionMs.toFloat() / model.state.durationMs
            } else 0f
            Scrubber(
                fraction = fraction,
                onSeek = actions.onSeek,
                enabled = seekEnabled,
            )
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                // The elapsed clock opens the sleep timer; the remaining
                // clock shows its countdown while one runs.
                Mono(
                    clock(model.state.positionMs),
                    KleeampType.time,
                    p.inkSecondary,
                    Modifier.microPress(onClick = actions.onOpenSleep),
                )
                if (model.reconnect > 0) {
                    Mono(
                        "reconnecting · ${model.reconnect}",
                        KleeampType.time,
                        p.amber,
                        maxLines = 1,
                    )
                } else {
                    Mono(
                        sleepRemaining(model) ?: "-" + clock(
                            (model.state.durationMs - model.state.positionMs).coerceAtLeast(0),
                        ),
                        KleeampType.time,
                        if (model.state.sleepAtMs != null) p.accent else p.inkSecondary,
                    )
                }
            }
        } else {
            StreamingRule(
                label = transportLabel(model),
                color = statusColor(model),
                dim = !model.state.playing && model.reconnect == 0,
            )
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Mono(
                    clock(model.state.positionMs),
                    KleeampType.time,
                    p.inkSecondary,
                    Modifier.microPress(onClick = actions.onOpenSleep),
                )
                // The station status always lives here: a live dot plus the
                // label (ON AIR, BUFFERING, PAUSED, …). The tap-the-meter
                // hint is gone.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusDot(model)
                    Mono(
                        statusLabel(model),
                        KleeampType.timeSmall,
                        if (model.state.playing || model.state.buffering ||
                            model.reconnect > 0 || model.error != null
                        ) {
                            statusColor(model)
                        } else {
                            p.inkFaint
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The prev / play-pause / next row — or the 5-key skip row on tracks. */
@Composable
internal fun TransportKeys(
    model: PlayerModel,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    if (model.showSkipKeys) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            MechKey(
                onClick = actions.onPrev,
                modifier = Modifier.weight(1f),
                enabled = model.state.hasPrev,
            ) { Icon(KleeampIcons.Prev, "previous station", Modifier.size(width = 21.dp, height = 17.dp)) }

            MechKey(onClick = actions.onSkipBack, modifier = Modifier.weight(0.7f)) {
                SkipKeyContent(icon = KleeampIcons.Rewind, description = "skip back", seconds = model.skipBackSec)
            }

            MechKey(
                onClick = actions.onPlayPause,
                modifier = Modifier.weight(1.6f),
                filled = true,
            ) {
                if (model.state.playing) {
                    Icon(KleeampIcons.Pause, "pause", Modifier.size(width = 20.dp, height = 22.dp))
                } else {
                    Icon(KleeampIcons.PlayTab, "play", Modifier.size(22.dp))
                }
            }

            MechKey(onClick = actions.onSkipFwd, modifier = Modifier.weight(0.7f)) {
                SkipKeyContent(icon = KleeampIcons.Forward, description = "skip forward", seconds = model.skipFwdSec)
            }

            MechKey(
                onClick = actions.onNext,
                modifier = Modifier.weight(1f),
                enabled = model.state.hasNext,
            ) { Icon(KleeampIcons.Next, "next station", Modifier.size(width = 21.dp, height = 17.dp)) }
        }
        return
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        MechKey(
            onClick = actions.onPrev,
            modifier = Modifier.weight(1f),
            enabled = model.state.hasPrev,
        ) { Icon(KleeampIcons.Prev, "previous station", Modifier.size(width = 21.dp, height = 17.dp)) }

        MechKey(
            onClick = actions.onPlayPause,
            modifier = Modifier.weight(1.7f),
            filled = true,
        ) {
            if (model.state.playing) {
                Icon(KleeampIcons.Pause, "pause", Modifier.size(width = 20.dp, height = 22.dp))
            } else {
                Icon(KleeampIcons.PlayTab, "play", Modifier.size(22.dp))
            }
        }

        MechKey(
            onClick = actions.onNext,
            modifier = Modifier.weight(1f),
            enabled = model.state.hasNext,
        ) { Icon(KleeampIcons.Next, "next station", Modifier.size(width = 21.dp, height = 17.dp)) }
    }
}
/** Circular-arrow skip key with its seconds: premium icon, honest number. */
@Composable
private fun SkipKeyContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    seconds: Int,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, "$description $seconds seconds", Modifier.size(13.dp))
        Mono("$seconds", KleeampType.meta, maxLines = 1)
    }
}

/** "-15s" / "+30s": a skip key always names its seconds. Pure for tests. */
internal fun skipLabel(seconds: Int): String =
    if (seconds < 0) "${seconds}s" else "+${seconds}s"

/** "SLEEP m:ss" countdown while a timer runs, else null. */
internal fun sleepRemaining(model: PlayerModel): String? =
    model.state.sleepAtMs?.let { at ->
        "SLEEP " + clock((at - System.currentTimeMillis()).coerceAtLeast(0))
    }

/**
 * Paging order for the meter swipe: next entry, wrapping past the end.
 * The gesture itself lives in [VisSkip], built on the same drag detector
 * the old swipe used so touch keeps working.
 */
internal fun List<Visualizer>.nextAfter(mode: Visualizer): Visualizer {
    if (isEmpty()) return mode
    val i = indexOf(mode).takeIf { it >= 0 } ?: 0
    return this[(i + 1) % size]
}

/** Previous entry in the swipe order, wrapping past the start. */
internal fun List<Visualizer>.prevBefore(mode: Visualizer): Visualizer {
    if (isEmpty()) return mode
    val i = indexOf(mode).takeIf { it >= 0 } ?: 0
    return this[(i - 1 + size) % size]
}

internal fun statusLabel(model: PlayerModel): String = when {
    model.reconnect > 0 -> "RECONNECTING · ${model.reconnect}"
    model.error != null -> "STREAM ERROR"
    model.state.buffering -> "BUFFERING"
    model.state.playing -> if (model.shownStation?.isTrack == true) "PLAYING" else "ON AIR"
    model.shownStation != null -> "PAUSED"
    else -> "NOTHING TUNED"
}

@Composable
internal fun statusColor(model: PlayerModel): androidx.compose.ui.graphics.Color {
    val p = LocalPalette.current
    return when {
        model.reconnect > 0 -> p.amber
        model.error != null -> p.destructiveInk
        model.state.buffering -> p.amber
        else -> p.accent
    }
}

/**
 * The live dot in front of the station status: breathes in accent while on
 * air, holds amber while buffering or reconnecting, red on stream error,
 * faint otherwise.
 */
@Composable
private fun StatusDot(model: PlayerModel, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val live = model.state.playing && model.reconnect == 0 &&
        model.error == null && !model.state.buffering
    val pulseTransition = rememberInfiniteTransition(label = "statusDot")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "statusDotAlpha",
    )
    val color = when {
        model.reconnect > 0 || model.state.buffering -> p.amber
        model.error != null -> p.destructiveInk
        model.state.playing -> p.accent
        else -> p.inkFaint
    }
    Canvas(modifier.size(7.dp)) {
        drawCircle(color.copy(alpha = if (live) pulse else 1f))
    }
}

internal fun transportLabel(model: PlayerModel): String = when {
    model.reconnect > 0 -> "reconnecting"
    model.error != null -> "no signal"
    model.state.buffering -> "buffering"
    model.state.playing -> if (model.shownStation?.isTrack == true) "playing" else "streaming"
    model.shownStation != null -> "paused"
    else -> "stopped"
}
