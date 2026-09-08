package stream.cliamp.mobile.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.window.Popup
import stream.cliamp.mobile.ui.theme.CliampShape
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalHapticsEnabled
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

/**
 * A key with real mechanical travel.
 *
 * Dark build: recessed bevel - a darker band welded to the bottom inside edge.
 * Light build: drop shelf - the face sits 3dp above a hairline slab.
 * Pressing collapses the travel either way, so both palettes feel like the
 * same physical switch rather than two different components.
 */
@Composable
fun MechKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    radius: Dp = CliampShape.key,
    filled: Boolean = false,
    enabled: Boolean = true,
    haptics: Boolean = true,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val travel = 3.dp
    val drop by animateDpAsState(if (pressed) travel else 0.dp, spring(stiffness = 1600f), label = "keyTravel")
    val hf = LocalHapticFeedback.current
    val hapticsOn = haptics && LocalHapticsEnabled.current

    val face = when {
        filled && p.dark -> p.accent
        filled && !p.dark -> p.ink
        p.dark -> p.keyFace
        else -> p.ground
    }
    val bevel = when {
        filled && p.dark -> p.accentBevel
        filled && !p.dark -> p.hairlineRegion
        p.dark -> p.keyBevel
        else -> p.hairline
    }
    val fg = when {
        filled && p.dark -> p.onAccent
        filled && !p.dark -> p.ground
        p.dark -> Color(0xFFDAE0DA)
        else -> p.ink
    }
    val travelPx = with(LocalDensity.current) { travel.toPx() }
    val collapse = (drop / travel).coerceIn(0f, 1f)

    Box(
        modifier
            .height(height + if (p.dark) 0.dp else travel)
            .alpha(if (enabled) 1f else 0.38f)
    ) {
        if (!p.dark) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .offset(y = travel)
                    .clip(RoundedCornerShape(radius))
                    .background(bevel)
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .offset(y = drop)
                .clip(RoundedCornerShape(radius))
                .background(face)
                .then(
                    if (p.dark) Modifier.drawBehind {
                        val h = travelPx * (1f - collapse)
                        if (h > 0.2f) {
                            drawRoundRect(
                                color = bevel,
                                topLeft = Offset(0f, size.height - h - 2f),
                                size = Size(size.width, h + 2f),
                                cornerRadius = CornerRadius(radius.toPx() / 2f, radius.toPx() / 2f),
                            )
                        }
                    } else Modifier
                )
                .then(
                    if (!filled) Modifier.border(1.dp, p.keyBorder, RoundedCornerShape(radius))
                    else Modifier
                )
                .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                    if (hapticsOn) hf.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides fg) { content() }
        }
    }
}

/** 11px uppercase filter chip. Selected = filled accent (dark) / filled ink (light). */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    val p = LocalPalette.current
    val fill = accent ?: if (p.dark) p.accent else p.ink
    val onFill = if (p.dark) p.onAccent else p.ground
    Box(
        modifier
            .microPress(onClick = onClick)
            .clip(RoundedCornerShape(CliampShape.small))
            .background(if (selected) fill else Color.Transparent)
            .then(if (selected) Modifier else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.small)))
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Mono(label.uppercase(), CliampType.chip, if (selected) onFill else p.inkTertiary, maxLines = 1)
    }
}

/** A single entry in a [ChipDropdown] menu. */
data class ChipOption(
    val label: String,
    val action: () -> Unit,
)

/**
 * A chip with a caret that opens a dropdown menu of [options] - the filter
 * chip's look, for cases where a value (a country, a sort order) is chosen
 * from a list rather than toggled. The current choice is the chip's label.
 */
@Composable
fun ChipDropdown(
    label: String,
    selected: Boolean,
    options: List<ChipOption>,
    menuWidth: Int = 180,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val fill = if (p.dark) p.accent else p.ink
    val onFill = if (p.dark) p.onAccent else p.ground
    Box(modifier) {
        Row(
            Modifier
                .microPress { open = !open }
                .clip(RoundedCornerShape(CliampShape.small))
                .background(if (selected) fill else Color.Transparent)
                .then(if (selected) Modifier else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(CliampShape.small)))
                .padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Mono(label.uppercase(), CliampType.chip, if (selected) onFill else p.inkTertiary, maxLines = 1)
            Icon(
                CliampIcons.CaretDown,
                null,
                Modifier.size(8.dp),
                tint = if (selected) onFill else p.inkTertiary,
            )
        }
        if (open) {
            Popup(
                onDismissRequest = { open = false },
                alignment = Alignment.TopStart,
                offset = IntOffset(0, 8),
            ) {
                LazyColumn(
                    Modifier.width(menuWidth.dp).heightIn(max = 300.dp).clip(RoundedCornerShape(CliampShape.small))
                        .background(p.ground).border(1.dp, p.hairlineRegion, RoundedCornerShape(CliampShape.small)),
                ) {
                    itemsIndexed(options) { i, opt ->
                        if (i > 0) HairlineDivider(region = true)
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                open = false
                                opt.action()
                            }.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Mono(opt.label, CliampType.chip, p.ink)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The shared back control: a rounded chip with a left arrow, matching the
 * Now Playing collapse chip so every screen's back affordance looks the same.
 */
@Composable
fun BackChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "back",
) {
    val p = LocalPalette.current
    Box(
        modifier
            .microPress(onClick = onClick)
            .clip(RoundedCornerShape(CliampShape.small))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(CliampIcons.Left, label, Modifier.size(width = 16.dp, height = 16.dp), tint = p.ink)
    }
}

/** 44x26 pill, 20dp knob, accent when on. */
@Composable
fun CliampToggle(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val knobX by animateDpAsState(if (on) 21.dp else 3.dp, spring(stiffness = 900f), label = "knob")
    Box(
        modifier
            .size(44.dp, 26.dp)
            .microPress { onChange(!on) }
            .clip(RoundedCornerShape(13.dp))
            .background(if (on) p.accent else if (p.dark) Color(0xFF262A26) else p.hairlineRegion),
    ) {
        Box(
            Modifier
                .offset(x = knobX, y = 3.dp)
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    when {
                        on && p.dark -> p.onAccent
                        on -> p.ground
                        p.dark -> p.inkTertiary
                        else -> p.ground
                    }
                )
        )
    }
}

/**
 * Square-ish handle with the same bevel/shelf as the keys - never a circle.
 * Track is a 6dp bar, filled portion accent.
 */
@Composable
fun MechSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    handle: Dp = 20.dp,
) {
    val p = LocalPalette.current
    val d = LocalDensity.current
    BoxWithConstraints(modifier.height(34.dp)) {
        val widthPx = with(d) { maxWidth.toPx() }
        val handlePx = with(d) { handle.toPx() }
        val span = (widthPx - handlePx).coerceAtLeast(1f)
        val frac = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

        fun emit(x: Float) {
            val f = ((x - handlePx / 2f) / span).coerceIn(0f, 1f)
            onValueChange(range.start + f * (range.endInclusive - range.start))
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(range, widthPx) { detectTapGestures { emit(it.x) } }
                .pointerInput(range, widthPx) {
                    detectHorizontalDragGestures { change, _ -> emit(change.position.x) }
                }
        ) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(6.dp).background(p.track))
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(frac).height(6.dp).background(p.accent))
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = with(d) { (frac * span).toDp() })
                    .size(handle)
                    .clip(RoundedCornerShape(CliampShape.tiny))
                    .background(if (p.dark) p.keyFace else p.ground)
                    .border(1.dp, p.keyBorder, RoundedCornerShape(CliampShape.tiny))
                    .drawBehind {
                        drawRect(
                            color = if (p.dark) p.keyBevel else p.hairline,
                            topLeft = Offset(0f, size.height - 3.dp.toPx()),
                            size = Size(size.width, 3.dp.toPx()),
                        )
                    }
            )
        }
    }
}

/** Vertical variant, used by the seven-band equaliser. */
@Composable
fun MechSliderVertical(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = -1f..1f,
) {
    val p = LocalPalette.current
    val d = LocalDensity.current
    BoxWithConstraints(modifier.width(30.dp)) {
        val hPx = with(d) { maxHeight.toPx() }
        val handlePx = with(d) { 18.dp.toPx() }
        val span = (hPx - handlePx).coerceAtLeast(1f)
        val frac = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

        fun emit(y: Float) {
            val f = 1f - ((y - handlePx / 2f) / span).coerceIn(0f, 1f)
            onValueChange(range.start + f * (range.endInclusive - range.start))
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(range, hPx) { detectTapGestures { emit(it.y) } }
                .pointerInput(range, hPx) { detectVerticalDragGestures { c, _ -> emit(c.position.y) } }
        ) {
            Box(Modifier.align(Alignment.TopCenter).width(6.dp).fillMaxHeight().background(p.track))
            Box(
                Modifier.align(Alignment.BottomCenter).width(6.dp)
                    .fillMaxHeight(maxOf(frac, 0.02f)).background(p.accent)
            )
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = with(d) { ((1f - frac) * span).toDp() })
                    .size(22.dp, 18.dp)
                    .clip(RoundedCornerShape(CliampShape.tiny))
                    .background(if (p.dark) p.keyFace else p.ground)
                    .border(1.dp, p.keyBorder, RoundedCornerShape(CliampShape.tiny))
                    .drawBehind {
                        drawRect(
                            color = if (p.dark) p.keyBevel else p.hairline,
                            topLeft = Offset(0f, size.height - 3.dp.toPx()),
                            size = Size(size.width, 3.dp.toPx()),
                        )
                    }
            )
        }
    }
}

/**
 * Full-width track, 4dp tall, with a 3dp vertical playhead in ink. Straight out
 * of the design doc, which specifies exactly this and a label under it naming
 * the gesture.
 *
 * Only drawn for sources that can actually seek. [StreamingRule] covers the
 * rest, and the player picks between them from what the player reports rather
 * than from the kind of station, so an on-demand HLS stream scrubs and an ICY
 * radio stream does not.
 */
@Composable
fun Scrubber(
    fraction: Float,
    modifier: Modifier = Modifier,
    onSeek: (Float) -> Unit,
) {
    val p = LocalPalette.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // While dragging, follow the finger; the player's own position lags behind
    // the gesture and would make the playhead stutter backwards.
    val shown = (if (dragging) dragFraction else fraction).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            // Tap and drag share ONE gesture handler. As separate pointerInput
            // blocks detectTapGestures consumed the down first, so the drag
            // detector that followed never saw an unconsumed down and drag-seek
            // silently never fired. Combined here: a press that clears touch
            // slop horizontally becomes a drag, anything else is a tap.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                    dragFraction = startFraction
                    val drag = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                    if (drag != null) {
                        dragging = true
                        drag(drag.id) { change ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    }
                    dragging = false
                    onSeek(if (drag != null) dragFraction else startFraction)
                }
            }
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp).background(p.track))
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(shown).height(4.dp).background(p.accent))
        Box(
            Modifier
                .offset(x = (maxWidth * shown - 1.5.dp).coerceIn(0.dp, maxWidth - 3.dp))
                .width(3.dp)
                .fillMaxHeight()
                .background(p.peak)
        )
        // The thumb is invisible until the finger lands - a small key that
        // floats over the playhead so a drag reads as grabbing the timeline.
        if (dragging) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (maxWidth * shown - 5.dp).coerceIn(0.dp, maxWidth - 10.dp))
                    .width(10.dp)
                    .clip(RoundedCornerShape(CliampShape.tiny))
                    .background(p.keyFace)
                    .border(1.dp, p.keyBorder, RoundedCornerShape(CliampShape.tiny))
                    .fillMaxHeight()
            )
        }
    }
}

/**
 * Radio has no timeline, so there is nothing to scrub. cliamp's TUI draws
 * `━━━ STREAMING ━━━` in place of the seek bar for any non-seekable source;
 * this is that, in the concept's geometry.
 */
@Composable
fun StreamingRule(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.accent,
    dim: Boolean = false,
) {
    val p = LocalPalette.current
    val rule = if (dim) p.track else color
    Row(
        modifier.fillMaxWidth().height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(4.dp).background(rule))
        Mono(label.uppercase(), CliampType.chip, if (dim) p.inkFaint else color)
        Box(Modifier.weight(1f).height(4.dp).background(rule))
    }
}

/**
 * A single-line label that never wraps: when the text is wider than the space
 * it has, it scrolls in place from the start through to the end (and loops).
 * Short names simply sit still. Keeps a long station name on one row instead of
 * wrapping down and shifting the layout.
 *
 * The text has to be measured unbounded - softWrap off and wrapContentWidth
 * unbounded - or Compose breaks it at the parent's edge to honour maxLines and
 * reports back a width that can never exceed the box, so nothing ever looks
 * like it overflows and the scroll never starts.
 */
@Composable
fun MarqueeLabel(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var boxWidth by remember { mutableFloatStateOf(0f) }
    var textWidth by remember { mutableFloatStateOf(0f) }
    // Overflow means the text is wider than the space it sits in.
    val overflow = textWidth > boxWidth && boxWidth > 0f

    val transition = rememberInfiniteTransition(label = "marquee")
    val travel = (textWidth + 24f).coerceAtLeast(1f)
    val offsetX by transition.animateFloat(
        initialValue = 0f,
        targetValue = -travel,
        animationSpec = infiniteRepeatable(
            // A reading pace, not a ticker: about 55px a second, and it sits
            // still for a moment first so the start of the name is readable
            // before anything moves.
            animation = tween(
                durationMillis = (travel * MARQUEE_MS_PER_PX).toInt().coerceAtLeast(4000),
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(MARQUEE_HOLD_MS, StartOffsetType.Delay),
        ),
        label = "marqueeScroll",
    )
    // Only run the animation when the text actually overflows; otherwise pin to 0.
    val x = if (overflow) offsetX else 0f

    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { boxWidth = it.width.toFloat() }
            .clipToBounds(),
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            onTextLayout = { r: TextLayoutResult ->
                textWidth = r.size.width.toFloat()
            },
            modifier = Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .offset(x = with(density) { x.toDp() }),
        )
    }
}

/** Milliseconds per pixel of travel - roughly 55px a second. */
private const val MARQUEE_MS_PER_PX = 18f

/** How long the label sits still before each pass. */
private const val MARQUEE_HOLD_MS = 1200
