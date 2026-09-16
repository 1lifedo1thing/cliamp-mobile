package stream.cliamp.mobile.ui.screens

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationArtSource
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.playback.PlayerState
import stream.cliamp.mobile.ui.clock
import stream.cliamp.mobile.ui.compact
import stream.cliamp.mobile.ui.components.BackChevron
import stream.cliamp.mobile.ui.components.BrickMeter
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.MechKey
import stream.cliamp.mobile.ui.components.MeterSize
import stream.cliamp.mobile.ui.components.MarqueeLabel
import stream.cliamp.mobile.ui.components.Scrubber
import stream.cliamp.mobile.ui.components.StreamingRule
import stream.cliamp.mobile.ui.components.ArtGlow
import stream.cliamp.mobile.ui.components.ArtPlate
import stream.cliamp.mobile.ui.components.microPress
import stream.cliamp.mobile.ui.components.rememberMeter
import stream.cliamp.mobile.ui.theme.CliampShape
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

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
    val spectrum: State<FloatArray>,
)

/** Every control the player screen can take, so both layouts share one set. */
private data class PlayerActions(
    val onBack: () -> Unit,
    val onOpenQueue: () -> Unit,
    val onToggleShuffle: () -> Unit,
    val onCycleSpeed: () -> Unit,
    val onOpenScope: () -> Unit,
    val onToggleFav: () -> Unit,
    val onSeek: (Float) -> Unit,
    val onPrev: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
)

@UnstableApi
@Composable
fun NowPlayingScreen(
    vm: NowPlayingViewModel,
    onOpenScope: () -> Unit,
    onOpenQueue: () -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current

    val uiState by vm.state.collectAsState()
    // rememberMeter reads its spectrum through Compose State, so hand it a
    // state that tracks the latest VM frame.
    val spectrum = rememberUpdatedState(uiState.spectrum)

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
        spectrum = spectrum,
    )
    val actions = PlayerActions(
        onBack = onBack,
        onOpenQueue = onOpenQueue,
        onToggleShuffle = { vm.player.toggleShuffle() },
        onCycleSpeed = { vm.onEvent(NowPlayingViewModel.Event.CycleSpeed) },
        onOpenScope = onOpenScope,
        onToggleFav = { vm.onEvent(NowPlayingViewModel.Event.ToggleFavorite) },
        onSeek = { vm.player.seekTo(it) },
        onPrev = { vm.player.prev() },
        onPlayPause = { vm.player.toggle(uiState.shownStation) },
        onNext = { vm.player.next() },
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
    }
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
            UpNextButton(model.upNextCount, actions.onOpenQueue)
        }
        Spacer(Modifier.height(8.dp))
        // The concept's art plate is `flex: 0 1 auto; max-height: 284px`, i.e.
        // it is the first thing to give way. Compose has no shrink factor, so
        // we measure the column and hand the plate whatever is left over -
        // otherwise the FAV row silently walks off the bottom of the frame.
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val reserved = 356.dp
            val artSide = minOf(maxWidth - Gutter * 2, (maxHeight - reserved)).coerceIn(96.dp, 340.dp)

            Column(
                Modifier
                    .fillMaxSize()
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
                    StationArt(
                        station = model.shownStation,
                        modifier = Modifier.align(Alignment.CenterHorizontally).size(artSide),
                    )

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
            StationArt(station = model.shownStation, modifier = Modifier.size(side))
            PlayerStatusRow(model, actions)
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
                UpNextButton(model.upNextCount, actions.onOpenQueue)
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
                .border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.small))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(CliampIcons.QueueTabLines, null, Modifier.size(14.dp), tint = p.accent)
            Mono("UP NEXT", CliampType.chip, p.ink, maxLines = 1)
            Mono(count.toString(), CliampType.chip, p.inkFaint, maxLines = 1)
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
            CliampIcons.PlayTiny,
            null,
            Modifier.size(width = 9.dp, height = 10.dp),
            tint = p.accent.copy(alpha = if (onAir) onAirAlpha else 1f),
        )
        Mono(
            statusLabel(model),
            CliampType.nowPlayingLabel,
            statusColor(model),
            modifier = Modifier.consumeAllGestures(),
        )
        Spacer(
            Modifier
                .weight(1f)
                .height(18.dp)
                .consumeAllGestures(),
        )
        SmallAction(
            CliampIcons.Shuffle,
            if (model.shuffled) "stop shuffling" else "shuffle",
            tint = if (model.shuffled) p.accent else p.inkSecondary,
        ) { actions.onToggleShuffle() }
        SpeedAction(speed = model.state.speed) { actions.onCycleSpeed() }
        SmallAction(CliampIcons.MeterSmall, "scope and equaliser", onClick = actions.onOpenScope)
        SmallAction(
            if (model.isFav) CliampIcons.StarFilled else CliampIcons.Star,
            if (model.isFav) "remove favourite" else "favourite",
            tint = if (model.isFav) p.accent else p.inkTertiary,
        ) { actions.onToggleFav() }
    }
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
            CliampType.trackTitle,
            p.ink,
            modifier = Modifier.consumeAllGestures(),
        )
        MarqueeLabel(
            model.streamTitle.ifBlank { model.error ?: artistOrTagLine(model.shownStation) },
            CliampType.rowPrimary,
            if (model.error != null && model.streamTitle.isBlank()) p.destructiveInk else p.inkSecondary,
            modifier = Modifier.consumeAllGestures(),
        )
        Mono(
            sourceLine(model.shownStation),
            CliampType.body,
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
            val frame = rememberMeter(
                columns = MeterSize.NowPlaying.columns,
                live = model.state.playing,
                spectrum = model.spectrum,
            )
            BrickMeter(
                frame = frame,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MeterSize.NowPlaying.height)
                    .consumeAllGestures(),
                brick = MeterSize.NowPlaying.brick,
                gap = MeterSize.NowPlaying.gap,
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
                Mono(clock(model.state.positionMs), CliampType.time, p.inkSecondary)
                Mono(
                    "-" + clock((model.state.durationMs - model.state.positionMs).coerceAtLeast(0)),
                    CliampType.time,
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
                Mono(clock(model.state.positionMs), CliampType.time, p.inkSecondary)
                Mono(
                    if (model.state.playing) "${model.state.bufferedMs / 1000}s buffered"
                    else "tap the meter for scope · eq",
                    CliampType.timeSmall,
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
        ) { Icon(CliampIcons.Prev, "previous station", Modifier.size(width = 21.dp, height = 17.dp)) }

        MechKey(
            onClick = actions.onPlayPause,
            modifier = Modifier.weight(1.7f),
            filled = true,
        ) {
            if (model.state.playing) {
                Icon(CliampIcons.Pause, "pause", Modifier.size(width = 20.dp, height = 22.dp))
            } else {
                Icon(CliampIcons.PlayTab, "play", Modifier.size(22.dp))
            }
        }

        MechKey(
            onClick = actions.onNext,
            modifier = Modifier.weight(1f),
            enabled = model.state.hasNext,
        ) { Icon(CliampIcons.Next, "next station", Modifier.size(width = 21.dp, height = 17.dp)) }
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
    return parts.joinToString(" · ").ifBlank { "12 cliamp channels · 50k+ directory" }
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
    var art by remember(station?.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(station?.id) {
        art = null
        val s = station ?: return@LaunchedEffect
        art = when {
            // local files carry a content:// uri, provider covers an http one,
            // and only radio needs the og:image discovery dance
            s.source == StationSource.Local ->
                stream.cliamp.mobile.data.LocalArt.bitmapFor(s.cover, context.contentResolver)
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
            // premium read. Same large radius as the plate itself. A touch
            // lighter on light grounds, where the same elevation reads
            // stronger against the pale ground.
            .shadow(if (p.dark) 26.dp else 20.dp, RoundedCornerShape(CliampShape.large))
            .graphicsLayer {
                scaleX = breath
                scaleY = breath
            }
            .consumeAllGestures(),
        radius = CliampShape.large,
        caption = if (art == null) caption else null,
    ) {
        art?.let { bmp ->
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
        // No cover at all: the broadcast glyph in accent, the same themed
        // mark the station's list rows wear - one identity in both places,
        // for cliamp channels and directory stations alike.
        if (art == null && station != null) {
            Icon(
                CliampIcons.StationsTab,
                null,
                Modifier
                    .align(Alignment.Center)
                    .size(96.dp),
                tint = p.accent,
            )
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
            .clip(RoundedCornerShape(CliampShape.small))
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
            .clip(RoundedCornerShape(CliampShape.small))
            .microPress(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Mono(
            speedLabel(speed),
            CliampType.meta,
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
