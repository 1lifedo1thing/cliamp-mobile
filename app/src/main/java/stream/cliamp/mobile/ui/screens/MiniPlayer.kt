package stream.cliamp.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.ui.components.BrickMeter
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.MeterSize
import stream.cliamp.mobile.ui.components.rememberMeter
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

/** The lockscreen widget's in-app twin: art plate, meter, one key. */
@Composable
fun MiniPlayer(
    station: Station?,
    streamTitle: String,
    playing: Boolean,
    buffering: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val p = LocalPalette.current
    if (station == null) return
    val frame = rememberMeter(columns = MeterSize.Mini.columns, live = playing)

    Column(Modifier.fillMaxWidth().background(p.panel)) {
        HairlineDivider(region = true)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = Gutter, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrickMeter(
                frame = frame,
                modifier = Modifier.size(width = 40.dp, height = MeterSize.Mini.height),
                brick = MeterSize.Mini.brick,
                gap = MeterSize.Mini.gap,
                columnGap = 2.dp,
                showPeaks = false,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Mono(station.name, CliampType.rowPrimaryMedium, p.ink, maxLines = 1)
                Mono(
                    when {
                        buffering -> "buffering…"
                        streamTitle.isNotBlank() -> streamTitle
                        station.source == StationSource.Cliamp -> "cliamp radio"
                        else -> station.meta.ifBlank { "live stream" }
                    },
                    CliampType.rowSecondary,
                    if (buffering) p.amber else p.inkTertiary,
                    maxLines = 1,
                )
            }
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (p.dark) p.accent else p.ink)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) CliampIcons.Pause else CliampIcons.PlayTab,
                    if (playing) "pause" else "play",
                    Modifier.size(if (playing) 13.dp else 15.dp),
                    tint = if (p.dark) p.onAccent else p.ground,
                )
            }
        }
    }
}
