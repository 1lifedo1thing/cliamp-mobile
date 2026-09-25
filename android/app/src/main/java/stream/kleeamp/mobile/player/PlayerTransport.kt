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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.pointerInput
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
import stream.kleeamp.mobile.chrome.OutputMenu
import stream.kleeamp.mobile.chrome.Scrubber
import stream.kleeamp.mobile.chrome.StreamingRule
import stream.kleeamp.mobile.chrome.ArtGlow
import stream.kleeamp.mobile.chrome.ArtPlate
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.chrome.rememberAudioOutputs
import stream.kleeamp.mobile.chrome.rememberStationThumbnail
import stream.kleeamp.mobile.player.vis.VisualizerMeter
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
) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        // The meter is the visualizer: when the setting is off it is
        // removed entirely, not just fed idle data - so neither the
        // frame loop nor a static brick grid exists in the player.
        if (model.visualizer != "off") {
            VisualizerMeter(
                mode = Visualizer.byId(model.visualizer),
                columns = MeterSize.NowPlaying.columns,
                live = model.state.playing,
                spectrumProvider = model.spectrumProvider,
                stereoProvider = model.stereoProvider,
                brick = MeterSize.NowPlaying.brick,
                gap = MeterSize.NowPlaying.gap,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MeterSize.NowPlaying.height)
                    .doubleTapToFullscreen(actions.onToggleFullscreen),
            )
        }

        // What the transport shows follows what the player says the
        // source can do, not what kind of station it is. A local file
        // and a provider track scrub; ICY radio does not.
        if (model.state.scrubbable && model.reconnect == 0 && model.error == null) {
            Scrubber(
                fraction = model.state.positionMs.toFloat() / model.state.durationMs,
                onSeek = actions.onSeek,
            )
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp).consumeAllGestures(),
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
                Mono(
                    sleepRemaining(model) ?: "-" + clock(
                        (model.state.durationMs - model.state.positionMs).coerceAtLeast(0),
                    ),
                    KleeampType.time,
                    if (model.state.sleepAtMs != null) p.accent else p.inkSecondary,
                )
            }
        } else {
            StreamingRule(
                label = transportLabel(model),
                modifier = Modifier.consumeAllGestures(),
                color = statusColor(model),
                dim = !model.state.playing && model.reconnect == 0,
            )
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp).consumeAllGestures(),
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

/** The prev / play-pause / next row. */
@Composable
internal fun TransportKeys(
    model: PlayerModel,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
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
/** "SLEEP m:ss" countdown while a timer runs, else null. */
internal fun sleepRemaining(model: PlayerModel): String? =
    model.state.sleepAtMs?.let { at ->
        "SLEEP " + clock((at - System.currentTimeMillis()).coerceAtLeast(0))
    }

internal fun statusLabel(model: PlayerModel): String = when {
    model.reconnect > 0 -> "RECONNECTING · ${model.reconnect}"
    model.error != null -> "STREAM ERROR"
    model.state.buffering -> "BUFFERING"
    model.state.playing -> "ON AIR"
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
    model.state.playing -> "streaming"
    model.shownStation != null -> "paused"
    else -> "stopped"
}
