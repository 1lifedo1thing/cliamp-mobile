package stream.cliamp.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import stream.cliamp.mobile.ui.theme.CliampShape
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

/** Screen gutter, fixed at 22dp everywhere in the concept. */
val Gutter = 22.dp

/**
 * Full width of the landscape tab rail, its separator included. Anything that
 * must clear the rail (like the floating settings arm) insets by this much
 * from the screen edge.
 */
val TabRailWidth = 79.dp

@Composable
fun HairlineDivider(modifier: Modifier = Modifier, region: Boolean = false) {
    val p = LocalPalette.current
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(if (region) p.hairlineRegion else p.hairline)
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 11.dp)
                    .background(p.accent)
            )
            Spacer(Modifier.width(7.dp))
            Mono(text.uppercase(), CliampType.sectionLabel, p.inkTertiary)
        }
        trailing?.invoke()
    }
}

/** The grid/list toggle at a section header. Shows the mode you switch *into*:
 * a grid glyph while listed, a list glyph while tiled. */
@Composable
fun GridListToggle(gridMode: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(34.dp)
            .microPress(onClick = onToggle)
            .clip(RoundedCornerShape(CliampShape.small))
            .background(if (p.dark) p.keyFace else p.ground)
            .border(1.dp, p.keyBorder, RoundedCornerShape(CliampShape.small)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (gridMode) CliampIcons.ListShort else CliampIcons.Grid,
            if (gridMode) "show as a list" else "show as a grid",
            Modifier.size(16.dp),
            tint = p.accent,
        )
    }
}

/** Fixed header: the system status bar inset, then title + filters. */
@Composable
fun ScreenHeader(
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .background(p.ground)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
    ) {
        content()
        if (divider) HairlineDivider(region = true)
    }
}

/** The one layout of the main app body, shared by every main-stream page.
 * It owns the whole fixed chrome - the status bar inset, the title row with
 * the search/settings corners wired in, the chip row, the divider - and hands
 * the page body below it to [content]. Each page passes in only its own
 * [title] (and any [chips]), so the chrome pixels are identical everywhere
 * while the title stays dynamic per page. The extended player and the finder
 * are separate surfaces and host their own layouts instead of this one. */
@Composable
fun MainLayout(
    title: String,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    chips: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier
            .fillMaxSize()
            .background(p.ground)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono(title, CliampType.screenTitle, p.ink, maxLines = 1)
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    CliampIcons.Search, "search",
                    Modifier
                        .size(22.dp)
                        .microPress(enabled = true, onClick = onOpenSearch),
                    tint = p.accent,
                )
                Icon(
                    CliampIcons.Gear, "settings",
                    Modifier
                        .size(22.dp)
                        .microPress(enabled = true, onClick = onOpenSettings),
                    tint = p.accent,
                )
            }
        }
        if (chips != null) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                chips()
            }
        }
        HairlineDivider(region = true)
        content()
    }
}

enum class Tab(val label: String) {
    // Play is gone: the full player opens from the mini-player bar. Servers is
    // gone too, folded into Library beside the other sources. Search is gone
    // too: it is a floating corner icon rather than a destination.
    Stations("STATIONS"), Pods("PODCASTS"), Lib("Library")
}

@Composable
fun CliampTabBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(modifier.fillMaxWidth().background(p.ground)) {
        HairlineDivider(region = true)
        Row(Modifier.fillMaxWidth()) {
            Tab.entries.forEach { tab ->
                TabItem(
                    tab = tab,
                    active = tab == current,
                    bottomInset = navBottom,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: Tab,
    active: Boolean,
    bottomInset: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val tint = if (active) p.accent else p.inkTertiary
    Column(
        modifier
            .microPress { onClick() }
            // 2px accent top border, pulled up 1dp so it sits on the divider
            .then(if (active) Modifier.offsetTopBorder(p.accent) else Modifier)
            .padding(top = 13.dp, bottom = 30.dp.coerceAtLeast(bottomInset + 8.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.height(17.dp), contentAlignment = Alignment.Center) {
            Icon(tabIcon(tab), null, Modifier.size(17.dp), tint = tint)
        }
        Mono(tab.label, CliampType.tabLabel, tint)
    }
}

private fun Modifier.offsetTopBorder(color: Color) = drawBehind {
    drawRect(color = color, topLeft = Offset(0f, -1.dp.toPx()), size = Size(size.width, 2.dp.toPx()))
}

/**
 * The landscape tab bar: a slim vertical rail on the right edge instead of
 * the bottom strip, so the horizontal frame keeps its full height for
 * content. Same three tabs, same active accent - just rotated.
 */
@Composable
fun CliampTabRail(
    current: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Row(modifier.fillMaxHeight().background(p.ground)) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(p.hairlineRegion))
        Column(
            Modifier
                .width(TabRailWidth - 1.dp)
                .fillMaxHeight()
                .padding(top = statusTop + 10.dp, bottom = navBottom + 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Tab.entries.forEach { tab ->
                RailItem(
                    tab = tab,
                    active = tab == current,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RailItem(
    tab: Tab,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val tint = if (active) p.accent else p.inkTertiary
    Column(
        modifier
            .width(70.dp)
            .microPress { onClick() }
            .then(if (active) Modifier.offsetRightBorder(p.accent) else Modifier)
            .clip(RoundedCornerShape(CliampShape.medium)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.height(17.dp), contentAlignment = Alignment.Center) {
            Icon(tabIcon(tab), null, Modifier.size(17.dp), tint = tint)
        }
        Mono(tab.label, CliampType.tabLabel, tint, maxLines = 1)
    }
}

private fun tabIcon(tab: Tab): ImageVector = when (tab) {
    Tab.Lib -> CliampIcons.LibTab
    Tab.Stations -> CliampIcons.StationsTab
    Tab.Pods -> CliampIcons.PodsTab
}

private fun Modifier.offsetRightBorder(color: Color) = drawBehind {
    drawRect(color = color, topLeft = Offset(size.width - 2.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
}

/**
 * Ambient glow behind a hero art plate: a soft radial wash of the accent
 * that lifts the artwork off the page. Static while a plate breathes, so
 * the two never fight. Pure backdrop - never intercepts gestures.
 */
@Composable
fun ArtGlow(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    // A light ground washes the wash out, so the halo runs stronger there.
    val coreAlpha = if (p.dark) 0.16f else 0.30f
    val midAlpha = if (p.dark) 0.05f else 0.10f
    Box(
        modifier
            .graphicsLayer {
                scaleX = 1.45f
                scaleY = 1.45f
            }
            .background(
                Brush.radialGradient(
                    0f to p.accent.copy(alpha = coreAlpha),
                    0.7f to p.accent.copy(alpha = midAlpha),
                    1f to Color.Transparent,
                ),
                CircleShape,
            ),
    )
}

/**
 * The plate that stands in for missing cover art: a flat surface, hairline
 * bordered. It never pretends to be art - no stripes, no patterns, nothing
 * that could be mistaken for a real cover. Pass [initial] to put the item's
 * first letter on the plate - the Library uses it only for playlist cover
 * previews, so the letter stays out of the mini player and the now-playing
 * screen, where the honest caption belongs instead.
 */
@Composable
fun ArtPlate(
    modifier: Modifier = Modifier,
    initial: String? = null,
    caption: String? = null,
    radius: Dp = CliampShape.medium,
    overlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
) {
    val p = LocalPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .border(1.dp, p.artBorder, RoundedCornerShape(radius))
            .background(p.artA)
    ) {
        overlay?.invoke(this)
        if (initial != null) {
            val glyph = initialOf(initial)
            if (glyph != null) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val size = maxWidth.coerceAtMost(maxHeight) * 0.5f
                    with(LocalDensity.current) {
                        Mono(
                            glyph,
                            CliampType.rowPrimaryMedium.copy(
                                fontSize = size.toPx().toSp(),
                                lineHeight = size.toPx().toSp(),
                            ),
                            p.inkFaint.copy(alpha = 0.75f),
                            Modifier.align(Alignment.Center),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (caption != null) {
            Mono(
                caption,
                CliampType.meta.copy(letterSpacing = 0.1.em),
                p.inkTertiary,
                Modifier.align(Alignment.BottomStart).padding(14.dp),
            )
        }
    }
}

/** The first letter of an item's name, for its plate monogram. */
private fun initialOf(name: String?): String? {
    if (name.isNullOrBlank()) return null
    return (name.firstOrNull { it.isLetter() }
        ?: name.firstOrNull { !it.isWhitespace() })
        ?.uppercaseChar()?.toString()
}

/** A hairline-separated list row. Cards are for objects with state, not lists.
 * [rail] paints a thin accent line down the leading edge - the shared marker
 * for "this is the row that is playing right now". */
@Composable
fun ListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    divider: Boolean = true,
    verticalPadding: Dp = 12.dp,
    rail: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    val railMod = if (rail)
        Modifier.drawWithContent {
            drawContent()
            drawRect(p.accent, topLeft = Offset(0f, 0f), size = Size(2.dp.toPx(), size.height))
        }
    else Modifier
    Column(modifier.then(railMod).fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.microPress(onClick = onClick) else Modifier)
                .padding(horizontal = Gutter, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { content() }
            trailing?.invoke(this)
        }
        if (divider) Box(Modifier.padding(start = Gutter)) { HairlineDivider() }
    }
}

/** A full-width muted note + divider, used for empty states and transient notices. */
@Composable
fun EmptyNote(text: String) {
    val p = LocalPalette.current
    Column {
        Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 18.dp)) {
            Mono(text, CliampType.rowSecondary, p.inkFaint)
        }
        HairlineDivider()
    }
}

/**
 * A fetch that auto-retried and gave up: a centered note with a manual TRY
 * AGAIN. [prominent] adds room above, for when the note is the whole page and
 * read as a state rather than a footnote under existing content.
 */
@Composable
fun RetryNote(
    message: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
) {
    val p = LocalPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Gutter, vertical = if (prominent) 96.dp else 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!message.isNullOrBlank()) {
            Mono(message, CliampType.rowSecondary, p.destructiveInk, maxLines = 2)
            Spacer(Modifier.height(14.dp))
        }
        Chip("try again", selected = false, onClick = onRetry, accent = p.ink)
    }
}
