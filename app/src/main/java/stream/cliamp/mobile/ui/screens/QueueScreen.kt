package stream.cliamp.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.playback.PlayerConnection
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.ScreenHeader
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

private enum class QueueTab(val label: String) {
    UpNext("up next"), Favorites("favourites"), History("history")
}

@UnstableApi
@Composable
fun QueueScreen(
    prefs: Prefs,
    player: PlayerConnection,
    current: Station?,
    playing: Boolean,
    onPlay: (Station, List<Station>) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(QueueTab.UpNext) }

    val favorites by prefs.favorites.collectAsState(initial = emptyList())
    val history by prefs.history.collectAsState(initial = emptyList())
    val queue = player.currentQueue

    val list = when (tab) {
        QueueTab.UpNext -> queue
        QueueTab.Favorites -> favorites
        QueueTab.History -> history
    }

    Column(Modifier.fillMaxSize().background(p.ground)) {
        ScreenHeader {
            Row(
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Mono("Queue", CliampType.screenTitle, p.ink)
                Mono(
                    when (tab) {
                        QueueTab.UpNext -> "${queue.size} stations"
                        QueueTab.Favorites -> "${favorites.size} starred"
                        QueueTab.History -> "${history.size} played"
                    },
                    CliampType.rowSecondary, p.inkTertiary,
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                QueueTab.entries.forEach { t -> Chip(t.label, tab == t, onClick = { tab = t }) }
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (list.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 28.dp)) {
                        Mono(
                            when (tab) {
                                QueueTab.UpNext -> "nothing queued — play a station and its list follows it here"
                                QueueTab.Favorites -> "no favourites yet — star a station from any list"
                                QueueTab.History -> "no history yet"
                            },
                            CliampType.rowSecondary, p.inkFaint,
                        )
                    }
                }
            }

            item {
                if (list.isNotEmpty()) {
                    SectionLabel(
                        when (tab) {
                            QueueTab.UpNext -> "in the list — ${list.size}"
                            QueueTab.Favorites -> "saved — ${list.size}"
                            QueueTab.History -> "recent — ${list.size}"
                        }
                    ) {
                        if (tab == QueueTab.History && history.isNotEmpty()) {
                            Mono(
                                "purge",
                                CliampType.meta,
                                p.destructiveInk,
                                Modifier.clickable { scope.launch { prefs.clearHistory() } },
                            )
                        }
                    }
                }
            }

            itemsIndexed(list, key = { _, it -> "${tab.name}:${it.url}" }) { idx, s ->
                val active = current?.url == s.url
                ListRow(
                    onClick = { onPlay(s, list); onOpenPlayer() },
                    verticalPadding = 11.dp,
                    leading = {
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(4.dp))
                                .then(
                                    if (active) Modifier.background(p.accent)
                                    else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(4.dp))
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (active && playing) {
                                Icon(CliampIcons.Pause, null, Modifier.size(9.dp), tint = p.onAccent)
                            } else {
                                Mono(
                                    "%02d".format((idx + 1).coerceAtMost(99)),
                                    CliampType.meta,
                                    if (active) p.onAccent else p.inkFaint,
                                )
                            }
                        }
                    },
                    trailing = {
                        if (tab == QueueTab.Favorites) {
                            Mono(
                                "DROP",
                                CliampType.tabLabel,
                                p.destructiveInk,
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { scope.launch { prefs.removeFavorite(s) } }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        } else {
                            Mono(
                                if (s.source == StationSource.Cliamp) "cliamp" else s.countryCode.lowercase(),
                                CliampType.meta,
                                if (s.source == StationSource.Cliamp) p.accent else p.inkFaint,
                            )
                        }
                    },
                ) {
                    Mono(
                        s.name,
                        if (active) CliampType.rowPrimaryMedium else CliampType.rowPrimary,
                        if (active) p.accent else p.ink,
                        maxLines = 1,
                    )
                    Mono(
                        if (active && playing) "playing" else s.meta.ifBlank { "live stream" },
                        CliampType.rowSecondary,
                        if (active && playing) p.accent else p.inkTertiary,
                        maxLines = 1,
                    )
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
