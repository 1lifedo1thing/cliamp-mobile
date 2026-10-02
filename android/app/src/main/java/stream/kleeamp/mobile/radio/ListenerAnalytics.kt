package stream.kleeamp.mobile.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.EmptyNote
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.theme.Mono
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * "Who's listening": the cliamp.stream stats section rebuilt for the app.
 * Real listener data drives the header totals, the globe markers and the
 * ranked leaderboard; with no live listeners the ranking falls back to
 * all-time sessions exactly like the website. There is no keyboard section
 * here - this feature is glanceable counts plus an explorable globe.
 */
@Composable
fun ListenerAnalytics(
    stats: CliampStats?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val geometry = rememberWorldGeometry()
    var selectedCode by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = stats?.countries.orEmpty()
    val selected = rows.firstOrNull { it.code == selectedCode }
    if (selectedCode != null && selected == null) selectedCode = null

    Column(modifier.fillMaxWidth()) {
        SectionLabel("who's listening — live", gutter = 8.dp) {
            Mono(
                if (stats?.isLive == true) "● live" else "○ all-time",
                KleeampType.meta,
                if (stats?.isLive == true) p.accent else p.inkFaint,
            )
        }
        // Header totals, like the website's LISTENERS / ON PLAYLISTS / COUNTRIES.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            StatBlock("listeners", stats?.activeNow?.let { "%,d".format(it) } ?: "–", true)
            StatBlock("on playlists", stats?.onPlaylists?.let { "%,d".format(it) } ?: "–", false)
            StatBlock("countries", rows.size.takeIf { stats?.isLive == true }?.let { "%,d".format(it) } ?: "–", false)
        }
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val wide = maxWidth >= 720.dp
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GlobeCard(
                        stats = stats,
                        geometry = geometry,
                        selectedCode = selectedCode,
                        onSelect = { selectedCode = it },
                        onRefresh = onRefresh,
                        modifier = Modifier.weight(1f),
                    )
                    CountrySide(
                        rows = rows,
                        isLive = stats?.isLive == true,
                        selectedCode = selectedCode,
                        onSelect = { selectedCode = it },
                        modifier = Modifier.weight(0.55f),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    GlobeCard(
                        stats = stats,
                        geometry = geometry,
                        selectedCode = selectedCode,
                        onSelect = { selectedCode = it },
                        onRefresh = onRefresh,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CountrySide(
                        rows = rows,
                        isLive = stats?.isLive == true,
                        selectedCode = selectedCode,
                        onSelect = { selectedCode = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        // Selected-country detail with close, kept inside the section.
        selected?.let { c ->
            val rank = rows.indexOf(c) + 1
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Mono(
                    "${c.name} · ${"%,d".format(c.listeners)} " +
                        "${if (stats?.isLive == true) "listening" else "sessions"} · #$rank",
                    KleeampType.rowSecondary,
                    p.ink,
                    modifier = Modifier.weight(1f),
                )
                Chip("close", selected = false, onClick = { selectedCode = null })
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, primary: Boolean) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Mono(value, KleeampType.trackTitleSmall, if (primary) p.accent else p.ink)
        Mono(label.uppercase(), KleeampType.meta, p.inkSecondary)
    }
}

@Composable
private fun GlobeCard(
    stats: CliampStats?,
    geometry: List<CountryGeometry>?,
    selectedCode: String?,
    onSelect: (String?) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(KleeampShape.small))
            .background(p.panel)
            .border(1.dp, p.hairline, RoundedCornerShape(KleeampShape.small)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono("$ cliamp radio --stats --globe", KleeampType.meta, p.inkSecondary)
            Mono(
                if (stats?.isLive == true) "● live" else "○ all-time",
                KleeampType.meta,
                if (stats?.isLive == true) p.accent else p.inkFaint,
            )
        }
        Box(
            Modifier.fillMaxWidth().height(340.dp)
                .background(p.ground, RoundedCornerShape(KleeampShape.small)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                geometry == null -> Mono("loading world map…", KleeampType.meta, p.inkFaint)
                stats == null -> EmptyGlobeNote("live statistics unavailable", onRefresh)
                stats.countries.isEmpty() -> EmptyGlobeNote("nobody is tuned in right now", onRefresh)
                else -> ListenerGlobe(
                    rows = stats.countries,
                    geometry = geometry,
                    selectedCode = selectedCode,
                    onSelect = onSelect,
                    modifier = Modifier.fillMaxWidth().height(340.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyGlobeNote(text: String, onRefresh: () -> Unit) {
    val p = LocalPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Mono(text, KleeampType.meta, p.inkFaint)
        Chip("try again", selected = false, onClick = onRefresh)
    }
}

/** Ranked country list: name + listeners with a max-relative bar, top 10. */
@Composable
private fun CountrySide(
    rows: List<CountryListeners>,
    isLive: Boolean,
    selectedCode: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono(
                if (isLive) "TOP COUNTRIES" else "TOP COUNTRIES · ALL-TIME",
                KleeampType.meta,
                p.inkSecondary,
            )
            Mono(if (isLive) "LISTENERS" else "SESSIONS", KleeampType.meta, p.inkSecondary)
        }
        if (rows.isEmpty()) {
            EmptyNote("nobody is tuned in right now")
        } else {
            val top = rows.take(10)
            val max = top.firstOrNull()?.listeners?.coerceAtLeast(1) ?: 1
            top.forEach { c ->
                val selected = selectedCode == c.code
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(KleeampShape.tiny))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = if (selected) "deselect ${c.name}" else "show ${c.name}",
                            onClick = { onSelect(if (selected) null else c.code) },
                        )
                        .background(if (selected) p.accentWash else p.ground)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Mono(
                            c.name,
                            KleeampType.rowSecondary,
                            if (selected) p.accent else p.ink,
                            maxLines = 1,
                        )
                        Box(
                            Modifier.fillMaxWidth().height(4.dp).padding(top = 5.dp)
                                .background(p.hairline, RoundedCornerShape(2.dp)),
                        ) {
                            val width = (c.listeners.toFloat() / max).coerceIn(0.02f, 1f)
                            Box(
                                Modifier.fillMaxWidth(width).height(4.dp)
                                    .background(p.accent, RoundedCornerShape(2.dp)),
                            )
                        }
                    }
                    Mono(
                        "%,d".format(c.listeners),
                        KleeampType.rowSecondary,
                        p.inkSecondary,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}
