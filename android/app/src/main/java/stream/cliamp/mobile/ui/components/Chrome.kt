package stream.cliamp.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
        Mono(text.uppercase(), CliampType.sectionLabel, p.inkTertiary)
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
            .clip(RoundedCornerShape(6.dp))
            .background(if (p.dark) p.keyFace else p.ground)
            .border(1.dp, p.keyBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle),
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

/** The identity strip that sits under the status bar on the player screen. */
@Composable
fun IdentityBar(left: String, right: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = Gutter, end = Gutter, top = 10.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(CliampIcons.Mark, null, Modifier.size(15.dp), tint = p.accent)
            Mono(left, CliampType.rowSecondary, p.accent)
        }
        Mono(right, CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
    }
}

enum class Tab(val label: String) {
    // Play is gone: the full player opens from the mini-player bar. Servers is
    // gone too, folded into Library beside the other sources. Search is gone
    // too: it is a floating corner icon rather than a destination.
    Stations("STATIONS"), Pods("PODCASTS"), Lib("Library")
}

/**
 * The two bare icons floating in the top-right corner of every tab: the
 * magnifier opens the app-wide finder, the gear opens settings. They take no
 * layout space — they overlay the screen via [Modifier.offset] and [align],
 * so the tabs keep their own full bleed. (The queue moved into the mini
 * player bar.) In landscape the corner belongs to the tab rail, so the pair
 * slides in to sit just clear of it via [endInset].
 */
@Composable
fun BoxScope.TabCorners(
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    endInset: Dp = Gutter,
) {
    val p = LocalPalette.current
    Row(
        modifier
            .align(Alignment.TopEnd)
            .offset(y = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            .padding(top = 12.dp, end = endInset),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            CliampIcons.Search, "search",
            Modifier
                .size(22.dp)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onOpenSearch),
            tint = p.accent,
        )
        Icon(
            CliampIcons.Gear, "settings",
            Modifier
                .size(22.dp)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onOpenSettings),
            tint = p.accent,
        )
    }
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
    bottomInset: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val tint = if (active) p.accent else p.inkTertiary
    Column(
        modifier
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            // 2px accent top border, pulled up 1dp so it sits on the divider
            .then(if (active) Modifier.offsetTopBorder(p.accent) else Modifier)
            .padding(top = 13.dp, bottom = 30.dp.coerceAtLeast(bottomInset + 8.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.height(17.dp), contentAlignment = Alignment.Center) {
            when (tab) {
                Tab.Lib -> Icon(CliampIcons.LibTab, null, Modifier.size(17.dp), tint = tint)
                Tab.Stations -> Icon(CliampIcons.StationsTab, null, Modifier.size(17.dp), tint = tint)
                Tab.Pods -> Icon(CliampIcons.PodsTab, null, Modifier.size(17.dp), tint = tint)
            }
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
            .then(if (active) Modifier.offsetRightBorder(p.accent) else Modifier)
            .clip(RoundedCornerShape(7.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.height(17.dp), contentAlignment = Alignment.Center) {
            when (tab) {
                Tab.Lib -> Icon(CliampIcons.LibTab, null, Modifier.size(17.dp), tint = tint)
                Tab.Stations -> Icon(CliampIcons.StationsTab, null, Modifier.size(17.dp), tint = tint)
                Tab.Pods -> Icon(CliampIcons.PodsTab, null, Modifier.size(17.dp), tint = tint)
            }
        }
        Mono(tab.label, CliampType.tabLabel, tint, maxLines = 1)
    }
}

private fun Modifier.offsetRightBorder(color: Color) = drawBehind {
    drawRect(color = color, topLeft = Offset(size.width - 2.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
}

/**
 * Album art is never invented: a 135-degree striped placeholder with a
 * monospace caption saying what belongs there.
 */
@Composable
fun StripedArt(
    modifier: Modifier = Modifier,
    caption: String? = null,
    badge: String? = null,
    radius: androidx.compose.ui.unit.Dp = 5.dp,
    stripe: androidx.compose.ui.unit.Dp = 6.dp,
    overlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
) {
    val p = LocalPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .border(1.dp, p.artBorder, RoundedCornerShape(radius))
            .drawBehind { drawStripes(p.artA, p.artB, stripe.toPx()) }
    ) {
        overlay?.invoke(this)
        if (caption != null) {
            Mono(
                caption,
                CliampType.meta.copy(letterSpacing = 0.1.em),
                p.inkTertiary,
                Modifier.align(Alignment.BottomStart).padding(14.dp),
            )
        }
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .border(1.dp, p.frameBorder, RoundedCornerShape(3.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Mono(badge, CliampType.tabLabel, p.inkSecondary)
            }
        }
    }
}

private fun DrawScope.drawStripes(a: Color, b: Color, w: Float) {
    clipRect {
        rotate(degrees = -45f, pivot = Offset(size.width / 2f, size.height / 2f)) {
            val diag = kotlin.math.hypot(size.width, size.height)
            val x0 = size.width / 2f - diag
            var x = x0
            var i = 0
            while (x < size.width / 2f + diag) {
                drawRect(
                    color = if (i % 2 == 0) a else b,
                    topLeft = Offset(x, size.height / 2f - diag),
                    size = Size(w, diag * 2f),
                )
                x += w
                i++
            }
        }
    }
}

/** A hairline-separated list row. Cards are for objects with state, not lists. */
@Composable
fun ListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    divider: Boolean = true,
    verticalPadding: androidx.compose.ui.unit.Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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

@Composable
fun IconLabelButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val p = LocalPalette.current
    val c = tint ?: p.accent
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, p.chipBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, null, Modifier.size(12.dp), tint = c)
        Mono(label.uppercase(), CliampType.chip, c)
    }
}

/** Scroll content that must clear the fixed tab bar. */
val ContentBottomPadding = PaddingValues(bottom = 24.dp)

@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = Column(modifier.fillMaxSize()) { content() }

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
