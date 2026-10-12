package stream.kleeamp.mobile.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.radio.CountryCount
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * The country picker both directory tabs share: every directory country in
 * one scrolling bottom sheet instead of a dropdown menu, "all countries"
 * first. A tap picks and dismisses. Counts are directory stations, so tabs
 * showing something else hide them.
 */
@Composable
fun CountrySheet(
    countries: List<CountryCount>,
    selectedIso: String?,
    showCounts: Boolean = true,
    onPick: (CountryCount?) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val all = selectedIso.isNullOrBlank()
    MenuSheetShell(
        onDismiss = onDismiss,
        halfHeight = true,
        header = {
            Mono(
                "countries",
                KleeampType.rowPrimaryMedium,
                p.ink,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        },
    ) {
        CountryRow(
            name = "all countries",
            sub = null,
            picked = all,
            onClick = { onPick(null); onDismiss() },
        )
        countries.forEach { c ->
            CountryRow(
                name = c.name.ifBlank { c.iso31661 },
                sub = if (showCounts && c.stationcount > 0) "%,d".format(c.stationcount) else null,
                picked = !all && c.iso31661.equals(selectedIso, ignoreCase = true),
                onClick = { onPick(c); onDismiss() },
            )
        }
    }
}

@Composable
private fun CountryRow(name: String, sub: String?, picked: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .microPress(onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Mono(name, KleeampType.rowPrimary, if (picked) p.accent else p.ink, maxLines = 1)
            sub?.let { Mono(it, KleeampType.rowSecondary, p.inkTertiary, maxLines = 1) }
        }
        if (picked) Icon(KleeampIcons.Check, null, Modifier.size(14.dp), tint = p.accent)
    }
}
