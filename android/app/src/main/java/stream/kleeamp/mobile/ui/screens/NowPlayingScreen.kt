package stream.kleeamp.mobile.ui.screens

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
import stream.kleeamp.mobile.data.PlaceholderArt
import stream.kleeamp.mobile.data.Station
import stream.kleeamp.mobile.data.StationArtSource
import stream.kleeamp.mobile.data.StationSource
import stream.kleeamp.mobile.data.visualizer.StereoMetrics
import stream.kleeamp.mobile.data.visualizer.Visualizer
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
import stream.kleeamp.mobile.ui.components.vis.VisualizerMeter
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

// Swallows taps, drags and swipes entirely so a gesture landing on the cover
// art or the inert strip around it can never fall through to advance or
// restart playback.
//
// Whole gestures are claimed, never individual events. A press nothing else
// wanted becomes ours and everything up to the release is eaten; a press a
// child already took is left alone from start to finish. That distinction is
// the whole point: consuming this node's MOVE events unconditionally cancels
// a child's pending tap the instant a finger drifts, because clickable drops
// a press as soon as it sees a consumed change. Fingers always drift, so
// play / pause did nothing at all while still taps worked.
//
// The Main pass is the one to do this on. A parent sees Main after its own
// children, so the transport keys and the back button claim their presses
// first and are never robbed. The screen stacked under this overlay also
// reads Main, and this one is above it, so a press that no child here wanted
// dies at this node instead of reaching the list underneath. The Final pass
// cannot do that job: every node in the tree gets Main before any node gets
// Final, so by then the screen behind has already taken the press.
private fun Modifier.consumeAllGestures(): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        down.consume()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/** Everything the player screen needs to draw, read once per frame. */
private data class PlayerModel(
    val state: PlayerState,
    val shownStation: Station?,
    val streamTitle: String,
    val upNextCount: Int,
    val reconnect: Int,
    val error: String?,
    val isFav: Boolean,
    val shuffled: Boolean,
    val visualizer: String,
    val spectrumProvider: () -> FloatArray?,
    val stereoProvider: () -> StereoMetrics?,
    val outputDevice: Int,
    val outputs: List<AudioOutput>,
    val currentOutput: AudioOutput?,
)

/** Every control the player screen can take, so both layouts share one set. */
private data class PlayerActions(
    val onBack: () -> Unit,
    val onOpenUpNext: () -> Unit,
    val onToggleShuffle: () -> Unit,
    val onCycleSpeed: () -> Unit,
    val onOpenScope: () -> Unit,
    val onToggleFav: () -> Unit,
    val onSeek: (Float) -> Unit,
    val onPrev: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onSelectOutput: (Int) -> Unit,
    val onToggleFullscreen: () -> Unit,
)

@UnstableApi
@Composable
fun NowPlayingScreen(
    vm: NowPlayingViewModel,
    onOpenScope: () -> Unit,
    onOpenUpNext: () -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    val uiState by vm.state.collectAsState()
    // The meters read the live analyser straight off the bus in their frame
    // loops: routing spectrum through VM state would recompose this whole
    // screen on every FFT callback and lag the visuals behind the music.
    // Lambdas are not effect keys, so re-creating them never restarts a loop.
    val spectrumProvider: () -> FloatArray? = { PlaybackBus.spectrum.value }
    val stereoProvider: () -> StereoMetrics? = { PlaybackBus.stereo.value }
    // The connected sinks and the one the stream is on: "speaker" until a
    // headset or Bluetooth route takes over.
    val context = LocalContext.current
    val outputs = rememberAudioOutputs()
    val currentOutput = remember(outputs, uiState.outputDevice) {
        AudioOutputs.current(context, uiState.outputDevice)
    }

    val model = PlayerModel(
        state = uiState.playerState,
        shownStation = uiState.shownStation,
        streamTitle = uiState.streamTitle,
        upNextCount = uiState.upNextCount,
        reconnect = uiState.reconnect,
        error = uiState.error,
        isFav = uiState.isFav,
        shuffled = uiState.shuffled,
        visualizer = uiState.visualizer,
        spectrumProvider = spectrumProvider,
        stereoProvider = stereoProvider,
        outputDevice = uiState.outputDevice,
        outputs = outputs,
        currentOutput = currentOutput,
    )
    val actions = PlayerActions(
        onBack = onBack,
        onOpenUpNext = onOpenUpNext,
        onToggleShuffle = { vm.player.toggleShuffle() },
        onCycleSpeed = { vm.onEvent(NowPlayingViewModel.Event.CycleSpeed) },
        onOpenScope = onOpenScope,
        onToggleFav = { vm.onEvent(NowPlayingViewModel.Event.ToggleFavorite) },
        onSeek = { vm.player.seekTo(it) },
        onPrev = { vm.player.prev() },
        onPlayPause = { vm.player.toggle(uiState.shownStation) },
        onNext = { vm.player.next() },
        onSelectOutput = { vm.onEvent(NowPlayingViewModel.Event.SetOutputDevice(it)) },
        onToggleFullscreen = { fullscreen = true },
    )

    Box(Modifier.fillMaxSize()) {
        // Whole-overlay blocker, drawn FIRST (bottom-most) so every interactive
        // control above it - the back key, transport, scrubber - hit-tests and
        // claims its own press before this ever sees it. Anything a control did
        // not take (the cover art, the inert text, the gaps between blocks) is
        // eaten here, so it can never fall through to the library list that
        // stays composed behind this overlay. Being a sibling (not an ancestor)
        // of the scrubber means it never swallows drag-to-seek.
        Box(Modifier.fillMaxSize().consumeAllGestures())

        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Wide frames (landscape phones, tablets on their side) split the
            // player across the frame: art on the left, transport on the right.
            // Portrait keeps the original single-column stack untouched.
            if (maxWidth > maxHeight) {
                LandscapePlayer(model, actions, frameWidth = maxWidth, frameHeight = maxHeight)
            } else {
                PortraitPlayer(model, actions)
            }
        }

        if (fullscreen) {
            FullscreenVisualizer(model) { fullscreen = false }
        }
    }
}

/** Columns the fullscreen meter spreads across a landscape frame. */
private const val FULLSCREEN_COLUMNS = 48

/**
 * The visualizer alone, edge to edge. Entering flips the activity to sensor
 * landscape and hides the bars; leaving puts both back. A double tap or Back
 * is the way out.
 */
@Composable
private fun FullscreenVisualizer(model: PlayerModel, onExit: () -> Unit) {
    val p = LocalPalette.current
    FullscreenEffect(onExit)
    Box(
        Modifier
            .fillMaxSize()
            .background(p.groundScope)
            .pointerInput(onExit) { detectTapGestures(onDoubleTap = { onExit() }) },
    ) {
        VisualizerMeter(
            mode = Visualizer.byId(model.visualizer),
            columns = FULLSCREEN_COLUMNS,
            live = model.state.playing,
            spectrumProvider = model.spectrumProvider,
            stereoProvider = model.stereoProvider,
            brick = 5.dp,
            gap = 4.dp,
            modifier = Modifier.fillMaxSize(),
        )
        Mono(
            "double tap to exit",
            KleeampType.meta,
            p.inkFaint,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun FullscreenEffect(onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        val window = activity?.window
        if (window == null) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousOrientation = activity.requestedOrientation
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = previousOrientation
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    BackHandler(onBack = onExit)
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Modifier.doubleTapToFullscreen(onToggle: () -> Unit): Modifier = pointerInput(onToggle) {
    detectTapGestures(onDoubleTap = { onToggle() })
}

/** The portrait player: art plate over text, meter, then the transport. */
@Composable
private fun PortraitPlayer(
    model: PlayerModel,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Column(modifier.fillMaxSize().background(p.ground).statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackChevron(actions.onBack)
            Spacer(Modifier.weight(1f))
            UpNextButton(model.upNextCount, actions.onOpenUpNext)
        }
        Spacer(Modifier.height(8.dp))
        // The concept's art plate is `flex: 0 1 auto; max-height: 284px`, i.e.
        // it is the first thing to give way. Compose has no shrink factor, so
        // the plate is given whatever height is left once the text block below
        // it has been measured - it shrinks on short frames or large font
        // scales instead of pushing the source line under the meter.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = Gutter),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            // The art plate and the text block below it share a flexed block
            // that absorbs however tall a long station name or stream title
            // grows, so the meter and the transport beneath stay pinned and
            // never shrink or shift when the names change length. Pinned to
            // the top (not centred) so the cover sits higher and the source
            // line under the artist can never be pushed off the bottom.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Top),
            ) {
                // Weights are measured after the text, so maxHeight is the
                // real leftover; fill = false keeps the plate hugging the
                // top and leaves the slack under the text, as before.
                BoxWithConstraints(
                    Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val side = minOf(maxWidth, maxHeight, 340.dp)
                    StationArt(
                        station = model.shownStation,
                        modifier = Modifier.size(side),
                    )
                }

                // The station name, stream title and meta line below the
                // plate are gesture-inert: taps and swipes on them (or
                // anywhere around the centre of the expanded player) can
                // never advance or restart the song. Only the small action
                // icons in the strip above stay live.
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    PlayerStatusRow(
                        model = model,
                        actions = actions,
                    )
                    PlayerMeta(model)
                }
            }

            PlayerTransport(model, actions)

            TransportKeys(model, actions)
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * The landscape player: art plate on the left, everything else on the right.
 * The transport keeps its full height of keys instead of conceding them to a
 * short portrait column, and the meter/queue/now-tuned rows pin to the bottom
 * of the right pane while the title block flexes above them.
 */
@Composable
private fun LandscapePlayer(
    model: PlayerModel,
    actions: PlayerActions,
    frameWidth: androidx.compose.ui.unit.Dp,
    frameHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Row(
        modifier
            .fillMaxSize()
            .background(p.ground)
            .statusBarsPadding()
            .padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val side = minOf(frameHeight - 76.dp, (frameWidth - Gutter * 2) * 0.44f).coerceIn(96.dp, 340.dp)
        Column(
            Modifier.weight(0.95f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            // The status badge and keys ride exactly under the cover: same
            // width, same edges, instead of the full column width.
            Column(
                Modifier.width(side),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StationArt(station = model.shownStation, modifier = Modifier.size(side))
                PlayerStatusRow(model, actions)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(
            Modifier
                .weight(1.05f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackChevron(actions.onBack)
                Spacer(Modifier.weight(1f))
                UpNextButton(model.upNextCount, actions.onOpenUpNext)
            }
            PlayerMeta(model)
            Spacer(Modifier.weight(1f))
            PlayerTransport(model, actions)
            TransportKeys(model, actions)
        }
    }
}

/** Compact outlined header control, with a full-height touch target. */
@Composable
private fun UpNextButton(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Box(
        modifier
            .height(48.dp)
            .semantics { role = Role.Button }
            .microPress(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.small))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(KleeampIcons.UpNextTabLines, null, Modifier.size(14.dp), tint = p.accent)
            Mono("UP NEXT", KleeampType.chip, p.ink, maxLines = 1)
            Mono(count.toString(), KleeampType.chip, p.inkFaint, maxLines = 1)
        }
    }
}

/** The status strip: ON AIR / BUFFERING badge, then the shuffle-scope-fav keys. */
@Composable
private fun PlayerStatusRow(
    model: PlayerModel,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    // LIVE: the little signal mark breathes in and out while the stream runs.
    val onAir = model.state.playing && model.reconnect == 0 && model.error == null
    val onAirTransition = rememberInfiniteTransition(label = "onAir")
    val onAirAlpha by onAirTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "onAirAlpha",
    )
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            KleeampIcons.PlayTiny,
            null,
            Modifier.size(width = 9.dp, height = 10.dp),
            tint = p.accent.copy(alpha = if (onAir) onAirAlpha else 1f),
        )
        Mono(
            statusLabel(model),
            KleeampType.nowPlayingLabel,
            statusColor(model),
            modifier = Modifier.consumeAllGestures(),
        )
        Spacer(
            Modifier
                .weight(1f)
                .height(18.dp)
                .consumeAllGestures(),
        )
        OutputAction(
            current = model.currentOutput,
            outputs = model.outputs,
            selectedId = model.outputDevice,
            onSelect = actions.onSelectOutput,
        )
        SmallAction(
            KleeampIcons.Shuffle,
            if (model.shuffled) "stop shuffling" else "shuffle",
            tint = if (model.shuffled) p.accent else p.inkSecondary,
        ) { actions.onToggleShuffle() }
        SpeedAction(speed = model.state.speed) { actions.onCycleSpeed() }
        SmallAction(KleeampIcons.MeterSmall, "scope and equaliser", onClick = actions.onOpenScope)
        SmallAction(
            if (model.isFav) KleeampIcons.StarFilled else KleeampIcons.Star,
            if (model.isFav) "remove favourite" else "favourite",
            tint = if (model.isFav) p.accent else p.inkTertiary,
        ) { actions.onToggleFav() }
    }
}

/**
 * The output key: one tap names the sink the stream is on and offers the other
 * connected ones. The icon tells the route at a glance, and it picks up the
 * accent when audio is not on the phone's own speaker.
 */
@Composable
private fun OutputAction(
    current: AudioOutput?,
    outputs: List<AudioOutput>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
) {
    val p = LocalPalette.current
    val external = current != null && current.kind != OutputKind.Speaker
    OutputMenu(
        trigger = { onOpen ->
            SmallAction(
                outputIcon(current?.kind),
                "audio output · ${current?.name ?: "system default"}",
                tint = if (external) p.accent else p.inkSecondary,
                onClick = onOpen,
            )
        },
        currentName = current?.name,
        outputs = outputs,
        selectedId = selectedId,
        onSelect = onSelect,
    )
}

private fun outputIcon(kind: OutputKind?): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    OutputKind.Headphones -> KleeampIcons.Headphones
    OutputKind.Bluetooth -> KleeampIcons.Bluetooth
    OutputKind.Usb -> KleeampIcons.Usb
    else -> KleeampIcons.Speaker
}

/** The station name, stream title and source meta line. All gesture-inert. */
@Composable
private fun PlayerMeta(
    model: PlayerModel,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        MarqueeLabel(
            model.shownStation?.name ?: "pick a station",
            KleeampType.trackTitle,
            p.ink,
            modifier = Modifier.consumeAllGestures(),
        )
        MarqueeLabel(
            model.streamTitle.ifBlank { model.error ?: artistOrTagLine(model.shownStation) },
            KleeampType.rowPrimary,
            if (model.error != null && model.streamTitle.isBlank()) p.destructiveInk else p.inkSecondary,
            modifier = Modifier.consumeAllGestures(),
        )
        Mono(
            sourceLine(model.shownStation),
            KleeampType.body,
            p.inkTertiary,
            modifier = Modifier.consumeAllGestures(),
            maxLines = 1,
        )
    }
}

/** The meter/scrubber zone plus its time readout, shared by both layouts. */
@Composable
private fun PlayerTransport(
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
                Mono(clock(model.state.positionMs), KleeampType.time, p.inkSecondary)
                Mono(
                    "-" + clock((model.state.durationMs - model.state.positionMs).coerceAtLeast(0)),
                    KleeampType.time,
                    p.inkSecondary,
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
                Mono(clock(model.state.positionMs), KleeampType.time, p.inkSecondary)
                Mono(
                    if (model.state.playing) "${model.state.bufferedMs / 1000}s buffered"
                    else "tap the meter for scope · eq",
                    KleeampType.timeSmall,
                    p.inkFaint,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The prev / play-pause / next row. */
@Composable
private fun TransportKeys(
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
private fun statusLabel(model: PlayerModel): String = when {
    model.reconnect > 0 -> "RECONNECTING · ${model.reconnect}"
    model.error != null -> "STREAM ERROR"
    model.state.buffering -> "BUFFERING"
    model.state.playing -> "ON AIR"
    model.shownStation != null -> "PAUSED"
    else -> "NOTHING TUNED"
}

@Composable
private fun statusColor(model: PlayerModel): androidx.compose.ui.graphics.Color {
    val p = LocalPalette.current
    return when {
        model.reconnect > 0 -> p.amber
        model.error != null -> p.destructiveInk
        else -> p.accent
    }
}

private fun transportLabel(model: PlayerModel): String = when {
    model.reconnect > 0 -> "reconnecting"
    model.error != null -> "no signal"
    model.state.buffering -> "buffering"
    model.state.playing -> "streaming"
    model.shownStation != null -> "paused"
    else -> "stopped"
}

/** The "cliamp radio · france · 123 votes" line under the stream title. */
private fun sourceLine(shownStation: Station?): String {
    val parts = buildList {
        shownStation?.let { s ->
            add(
                when (s.source) {
                    StationSource.Cliamp -> "cliamp radio"
                    StationSource.Directory -> "directory"
                    StationSource.Local -> "on device"
                    StationSource.Provider -> "provider"
                    StationSource.Podcast -> "podcast"
                    StationSource.Custom -> "custom"
                }
            )
            if (s.country.isNotBlank() && s.source != StationSource.Cliamp) add(s.country.lowercase())
            if (s.votes > 0) add("${compact(s.votes)} votes")
        }
    }
    return parts.joinToString(" · ").ifBlank { "15 cliamp channels · 50k+ directory" }
}

/** Synchronous memory peek behind [StationArt]: the same branches as its async
 * resolve, but LRU gets only, so a known cover paints on the first frame. */
private fun peekArt(station: Station?): ImageBitmap? {
    if (station == null) return null
    val bmp = when {
        station.source == StationSource.Local ->
            stream.kleeamp.mobile.data.LocalArt.cached(station.cover)
                ?: StationArtSource.cached(station)
        station.cover.startsWith("http") ->
            StationArtSource.cachedUrl(station.cover)
                ?: StationArtSource.cached(station)
        else -> StationArtSource.cached(station)
    }
    return bmp?.asImageBitmap()
}

/**
 * Art is never invented, but it is not always absent either. Directory stations
 * usually publish an og:image on their homepage, and that is the station's own
 * branding rather than something we made up, so it is shown when it exists and
 * the striped plate stands in when it does not.
 *
 * The logo is contained, not cropped: most og:images are 1200x630 wordmarks and
 * a square centre crop cuts them in half.
 */
@Composable
private fun StationArt(station: Station?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val context = LocalContext.current
    // Paint what memory already holds synchronously, so a song change shows
    // its cover on the first frame instead of flashing the empty plate while
    // the async lookup below re-resolves what is already known. The row
    // thumbnail doubles as a progressive preview: soft for a frame or two,
    // then replaced by the full art - still the new item, never empty.
    var art by remember(station?.id) { mutableStateOf(peekArt(station)) }
    val preview = station?.let { rememberStationThumbnail(it, fallback = false) }
    // No cover of its own: one of the bundled designs stands in - except
    // local and provider songs, which wear the empty plate instead. The pick
    // is stable per station, so the plate does not reshuffle on every change.
    val placeholderKey = station?.id?.ifBlank { station.url }
    val placeholder = remember(placeholderKey) {
        if (station?.bundledCover != false) {
            placeholderKey?.takeIf { it.isNotEmpty() }
                ?.let { PlaceholderArt.bitmapFor(context, it)?.asImageBitmap() }
        } else null
    }
    LaunchedEffect(station?.id) {
        if (art != null) return@LaunchedEffect
        val s = station ?: return@LaunchedEffect
        art = when {
            // local files carry a content:// uri, provider covers an http one,
            // and only radio needs the og:image discovery dance
            s.source == StationSource.Local ->
                stream.kleeamp.mobile.data.LocalArt.bitmapFor(s.cover, context.contentResolver)
                    ?: StationArtSource.bitmapFor(s) // else embedded album art
            s.cover.startsWith("http") -> StationArtSource.bitmapForUrl(s.cover)
            else -> StationArtSource.bitmapFor(s)
        }?.asImageBitmap()
    }
    val caption = when {
        station == null -> "[ no station tuned ]"
        station.source == StationSource.Local ->
            if (station.album.isNotBlank()) "[ ${station.album.lowercase()} · ${station.artist.lowercase()} ]"
            else "[ local file ]"
        station.source == StationSource.Provider ->
            if (station.album.isNotBlank()) "[ ${station.album.lowercase()} · ${station.artist.lowercase()} ]"
            else "[ provider ]"
        station.source == StationSource.Podcast ->
            if (station.artist.isNotBlank()) "[ ${station.artist.lowercase()} · podcast ]"
            else "[ podcast ]"
        station.source == StationSource.Cliamp -> "[ ${station.slug} · cliamp radio ]"
        station.countryCode.isNotBlank() -> "[ ${station.countryCode.lowercase()} · live stream ]"
        else -> "[ live stream ]"
    }
    // The plate breathes very slowly while it is up - a 1% swell over several
    // seconds, in place, so the artwork feels alive rather than printed.
    val breathTransition = rememberInfiniteTransition(label = "artBreath")
    val breath by breathTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(tween(7000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "artBreathScale",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        ArtGlow(Modifier.fillMaxSize())
    ArtPlate(
        modifier = Modifier
            .fillMaxSize()
            // Soft drop shadow so the plate floats over the page - the
            // premium read, same large radius as the plate itself. A touch
            // lighter on light grounds, where the same elevation reads
            // stronger against the pale ground.
            .shadow(if (p.dark) 26.dp else 20.dp, RoundedCornerShape(KleeampShape.large))
            .graphicsLayer {
                scaleX = breath
                scaleY = breath
            }
            .consumeAllGestures(),
        radius = KleeampShape.large,
        caption = if (art == null && preview == null && placeholder == null) caption else null,
    ) {
        (art ?: preview)?.let { bmp ->
            // Real album art is square and fills the plate edge to edge. Radio
            // art does not have to be: og:images are typically 1200x630
            // wordmarks, and cropping one to a square cuts it in half, so
            // those stay inset and contained - unless the bitmap itself is
            // square enough (a station logo rather than a wordmark) and big
            // enough not to turn to mush, in which case it fills like album
            // art instead of floating small in the middle of the plate.
            // Podcast artwork is square by Apple's own requirement, so it
            // belongs with the album art that fills the plate, not with the
            // 1200x630 radio wordmarks that have to stay inset.
            val albumArt = station?.source == StationSource.Local ||
                station?.source == StationSource.Provider ||
                station?.source == StationSource.Podcast
            val squarish = run {
                val w = bmp.width.coerceAtLeast(1)
                val h = bmp.height.coerceAtLeast(1)
                val aspect = w.toFloat() / h
                aspect in 0.85f..1.18f && minOf(w, h) >= 96
            }
            val fills = albumArt || squarish
            Image(
                bitmap = bmp,
                contentDescription = station?.name,
                modifier =
                    if (fills) Modifier.fillMaxSize()
                    else Modifier.fillMaxSize().padding(14.dp),
                contentScale = if (fills) ContentScale.Crop else ContentScale.Fit,
            )
        }
        // No cover at all: a bundled stand-in where allowed, otherwise an
        // honest glyph rather than a blank hole.
        if (art == null && preview == null) {
            placeholder?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = station?.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Mono(
                    "no cover · random art",
                    KleeampType.meta,
                    p.inkTertiary,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .background(p.ground.copy(alpha = 0.72f), RoundedCornerShape(KleeampShape.tiny))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    maxLines = 1,
                )
            } ?: station?.let {
                Icon(
                    KleeampIcons.MusicNote,
                    it.name,
                    Modifier.align(Alignment.Center).size(64.dp),
                    tint = p.accent,
                )
            }
        }
    }
}
}

/** A 15dp icon in a 28dp tap target, sized for a secondary action. */
@Composable
private fun SmallAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .microPress(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(15.dp), tint = tint ?: p.inkSecondary)
    }
}

private fun speedLabel(v: Float): String {
    val s = if (v % 1f == 0f) v.toInt().toString() else v.toString().trimEnd('0')
    return "${s}×"
}

/** Playback speed as a terse mono key: taps step through the ladder. */
@Composable
private fun SpeedAction(speed: Float, onClick: () -> Unit) {    val p = LocalPalette.current
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .microPress(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Mono(
            speedLabel(speed),
            KleeampType.meta,
            if (speed != 1f) p.accent else p.inkSecondary,
            maxLines = 1,
        )
    }
}

/**
 * The line beneath the track title in the expanded player: the artist for
 * local files (and podcasts), the stream title normally, and the station tags
 * as a last resort - mirroring the mini player's artist line so the artist is
 * always named under the song.
 */
private fun artistOrTagLine(station: Station?): String {
    if (station == null) return ""
    return when (station.source) {
        StationSource.Local -> station.artistAlbum
        StationSource.Podcast -> station.artist
        else -> station.tagList.take(3).joinToString(" · ")
    }.ifBlank { station.tagList.take(3).joinToString(" · ") }
}
