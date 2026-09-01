package stream.cliamp.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.CliampStats
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.Repository
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationStats
import stream.cliamp.mobile.ui.components.BrickBars
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.CliampToggle
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.IconLabelButton
import stream.cliamp.mobile.ui.components.Panel
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.components.StatusPill
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

/**
 * The concept's "remote hosts" screen, pointed at the thing that actually is
 * remote here: cliamp's own broadcast network. Amber keeps its meaning - it
 * marks state that lives on somebody else's machine.
 */
@Composable
fun StatsScreen(
    repository: Repository,
    prefs: Prefs,
    onBack: () -> Unit,
    onPlay: (Station) -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val stats by repository.stats.collectAsState()
    val channels by repository.cliamp.collectAsState()
    val dirStats by repository.directoryStats.collectAsState()
    val cellular by prefs.cellular.collectAsState(initial = true)
    var expanded by remember { mutableStateOf<String?>(null) }

    // Listener counts move; poll while the screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            repository.refreshStats()
            delay(30_000)
        }
    }

    Column(Modifier.fillMaxSize().background(p.ground).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.clickable(onClick = onBack),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(CliampIcons.Prev, "back", Modifier.size(width = 15.dp, height = 12.dp), tint = p.inkSecondary)
                Mono("back", CliampType.rowSecondary, p.inkSecondary)
            }
            Mono(
                stats?.let { "${it.activeListeners} listening now" } ?: "…",
                CliampType.rowSecondary, p.amber,
            )
        }
        HairlineDivider(region = true)

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                Column(Modifier.padding(horizontal = Gutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Mono("cliamp radio", CliampType.screenTitle, p.ink)
                    Mono(
                        "twelve channels running on cliamp's own icecast. the directory adds " +
                            (dirStats?.playable?.let { "%,d".format(it) } ?: "50,000+") +
                            " community stations on top.",
                        CliampType.body, p.inkSecondary,
                    )
                }
            }

            item { StatTotals(stats) }

            item { SectionLabel("channels — ${channels.size}") }

            items(channels, key = { "st:${it.slug}" }) { station ->
                val s = stats?.stations?.get(station.slug)
                ChannelCard(
                    name = station.name,
                    slug = station.slug,
                    stats = s,
                    expanded = expanded == station.slug,
                    onToggle = { expanded = if (expanded == station.slug) null else station.slug },
                    onPlay = { onPlay(station) },
                )
            }

            item {
                Column(Modifier.padding(top = 8.dp)) {
                    SectionLabel("network")
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Mono("Stream over cellular", CliampType.rowPrimary, p.ink)
                            Mono(
                                if (cellular) "on — full bitrate away from wifi"
                                else "off — wifi only",
                                CliampType.rowSecondary, p.inkTertiary,
                            )
                        }
                        CliampToggle(cellular, onChange = { scope.launch { prefs.setCellular(it) } })
                    }
                    HairlineDivider()
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun StatTotals(stats: CliampStats?) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Totals("sessions", stats?.totalSessions?.let(::compact) ?: "…", Modifier.weight(1f))
        Totals("hours", stats?.totalListenHours?.let { compact(it.toLong()) } ?: "…", Modifier.weight(1f))
        Totals("peak", stats?.peakListeners?.toString() ?: "…", Modifier.weight(1f), accent = p.amber)
    }
}

@Composable
private fun Totals(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: androidx.compose.ui.graphics.Color? = null,
) {
    val p = LocalPalette.current
    Panel(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Mono(value, CliampType.trackTitleCompact, accent ?: p.ink, maxLines = 1)
            Mono(label.uppercase(), CliampType.tabLabel, p.inkTertiary)
        }
    }
}

@Composable
private fun ChannelCard(
    name: String,
    slug: String,
    stats: StationStats?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
) {
    val p = LocalPalette.current
    val live = (stats?.activeListeners ?: 0) > 0

    Box(Modifier.padding(horizontal = Gutter, vertical = 5.dp)) {
        Panel(borderColor = if (live) p.amber.copy(alpha = 0.45f) else null) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onToggle),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Mono(name, CliampType.trackTitleSmall, p.ink, maxLines = 1)
                        Mono("radio.cliamp.stream/$slug", CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
                    }
                    StatusPill(
                        if (live) "${stats?.activeListeners} live" else "idle",
                        if (live) p.amber else p.inkFaint,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Field("sessions", stats?.totalSessions?.let(::compact) ?: "-")
                    Field("hours", stats?.totalListenHours?.let { compact(it.toLong()) } ?: "-")
                    Field("peak", stats?.peakListeners?.toString() ?: "-")
                }

                // 31 days of sessions, drawn on the same brick grid as the meter
                stats?.daily?.takeIf { it.isNotEmpty() }?.let { daily ->
                    val max = daily.maxOf { it.sessions }.coerceAtLeast(1)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BrickBars(
                            values = daily.map { it.sessions.toFloat() / max },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            brick = 3.dp, gap = 2.dp, columnGap = 2.dp,
                            litColor = p.accent,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Mono(daily.first().date.takeLast(5), CliampType.meta, p.inkFaint)
                            Mono("${daily.size} days · peak ${compact(max)}", CliampType.meta, p.inkFaint)
                            Mono(daily.last().date.takeLast(5), CliampType.meta, p.inkFaint)
                        }
                    }
                }

                if (expanded) {
                    stats?.topCountries?.takeIf { it.isNotEmpty() }?.let { list ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Mono("TOP COUNTRIES", CliampType.sectionLabel, p.inkTertiary)
                            list.take(5).forEach { c ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Mono(c.country.lowercase(), CliampType.rowSecondary, p.inkSecondary, maxLines = 1)
                                    Mono(compact(c.sessions), CliampType.rowSecondary, p.inkTertiary)
                                }
                            }
                        }
                    }
                    stats?.topCities?.takeIf { it.isNotEmpty() }?.let { list ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Mono("TOP CITIES", CliampType.sectionLabel, p.inkTertiary)
                            list.take(5).forEach { c ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Mono(
                                        "${c.city.lowercase()} · ${c.countryCode.lowercase()}",
                                        CliampType.rowSecondary, p.inkSecondary, maxLines = 1,
                                    )
                                    Mono(compact(c.sessions), CliampType.rowSecondary, p.inkTertiary)
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconLabelButton(CliampIcons.PlayRow, "tune in", onClick = onPlay)
                    IconLabelButton(
                        if (expanded) CliampIcons.Minus else CliampIcons.Plus,
                        if (expanded) "less" else "detail",
                        onClick = onToggle,
                        tint = p.inkSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Mono(label.uppercase(), CliampType.tabLabel, p.inkTertiary)
        Mono(value, CliampType.rowPrimaryMedium, p.ink)
    }
}

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fm".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
