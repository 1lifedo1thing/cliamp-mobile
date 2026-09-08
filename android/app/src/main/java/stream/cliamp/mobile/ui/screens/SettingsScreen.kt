package stream.cliamp.mobile.ui.screens

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import stream.cliamp.mobile.BuildConfig
import stream.cliamp.mobile.R
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.Repository
import stream.cliamp.mobile.widget.CliampWidgetReceiver
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.CliampToggle
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.MechSlider
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.theme.AmberPalette
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.DarkPalette
import stream.cliamp.mobile.ui.theme.LightPalette
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.OmarchyPalettes
import stream.cliamp.mobile.ui.theme.OxideLightPalette
import stream.cliamp.mobile.ui.theme.OxidePalette
import stream.cliamp.mobile.ui.theme.OmarchyThemeKeys
import stream.cliamp.mobile.ui.theme.Mono
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    prefs: Prefs,
    repository: Repository,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val palette by prefs.palette.collectAsState(initial = "dark")
    val haptics by prefs.haptics.collectAsState(initial = true)
    val visualizer by prefs.visualizer.collectAsState(initial = "spectrum")
    val cellular by prefs.cellular.collectAsState(initial = true)
    val buffer by prefs.bufferSeconds.collectAsState(initial = 20)
    val autoResume by prefs.autoResume.collectAsState(initial = false)
    val history by prefs.history.collectAsState(initial = emptyList())
    val favorites by prefs.favorites.collectAsState(initial = emptyList())
    val dirStats by repository.directoryStats.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(p.ground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("‹ back", selected = false, onClick = onBack)
        }
        Box(Modifier.padding(horizontal = Gutter, vertical = 4.dp)) {
            Mono("Settings", CliampType.screenTitle, p.ink)
        }
        Spacer(Modifier.height(12.dp))
        HairlineDivider(region = true)

        SectionLabel("playback")
        ToggleRow(
            title = "Auto-resume",
            subtitle = if (autoResume) "on — retune the last station at launch" else "off",
            checked = autoResume,
            onChange = { scope.launch { prefs.setAutoResume(it) } },
        )
        ToggleRow(
            title = "Stream over cellular",
            subtitle = if (cellular) "on — full bitrate away from wifi" else "off — wifi only",
            checked = cellular,
            onChange = { scope.launch { prefs.setCellular(it) } },
        )
        Column(Modifier.padding(horizontal = Gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Mono("Buffer", CliampType.rowPrimary, p.ink)
                Mono("${buffer}s", CliampType.rowSecondary, p.accent)
            }
            MechSlider(
                value = buffer.toFloat(),
                onValueChange = { scope.launch { prefs.setBufferSeconds(it.roundToInt()) } },
                range = 5f..60f,
                modifier = Modifier.fillMaxWidth(),
            )
            Mono("deeper buffers survive a bad tunnel, at the cost of latency", CliampType.meta, p.inkFaint)
        }
        HairlineDivider()

        SectionLabel("feel")
        ToggleRow(
            title = "Key haptics",
            subtitle = if (haptics) "on" else "off",
            checked = haptics,
            onChange = { scope.launch { prefs.setHaptics(it) } },
        )
        ChoiceRow(
            title = "Visualizer",
            options = listOf("spectrum", "off"),
            selected = visualizer,
            onSelect = { scope.launch { prefs.setVisualizer(it) } },
        )
        HairlineDivider()

        SectionLabel("themes — ${OmarchyThemeKeys.size + 6}")
        listOf(
            // system first: it is the default, and its swatch is whichever
            // half of oxide the device is currently asking for.
            "system" to if (p.dark) OxidePalette else OxideLightPalette,
            "oxide" to OxidePalette,
            "oxide-light" to OxideLightPalette,
            "amber" to AmberPalette,
            "dark" to DarkPalette,
            "light" to LightPalette,
        ).forEach { (key, theme) ->
            ThemeRow(
                key = key,
                theme = theme,
                selected = palette == key,
                subtitle = if (key == "system") "follows the device" else null,
                onSelect = { scope.launch { prefs.setPalette(key) } },
            )
        }
        OmarchyThemeKeys.forEach { key ->
            ThemeRow(
                key = key,
                theme = OmarchyPalettes.getValue(key),
                selected = palette == key,
                onSelect = { scope.launch { prefs.setPalette(key) } },
            )
        }
        HairlineDivider()

        SectionLabel("home screen")
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    val mgr = AppWidgetManager.getInstance(context)
                    val provider = ComponentName(context, CliampWidgetReceiver::class.java)
                    if (mgr.isRequestPinAppWidgetSupported) {
                        mgr.requestPinAppWidget(provider, null, null)
                    }
                }
                .padding(horizontal = Gutter, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Mono("Add widget", CliampType.rowPrimary, p.ink)
                Mono("station, transport and quick tune", CliampType.rowSecondary, p.inkTertiary)
            }
            Mono("+", CliampType.rowPrimary, p.accent)
        }
        Box(Modifier.padding(start = Gutter)) { HairlineDivider() }
        InfoRow("Quick settings tile", "add from the shade editor")
        HairlineDivider()

        SectionLabel("storage")
        InfoRow("Favourites", "${favorites.size} stations")
        InfoRow("History", "${history.size} entries")
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { scope.launch { prefs.clearHistory() } }
                .padding(horizontal = Gutter, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono("Purge history", CliampType.rowPrimary, p.destructiveInk)
            Mono("▸", CliampType.rowPrimary, p.destructiveInk)
        }
        HairlineDivider()
        InfoRow(
            "Directory",
            dirStats?.let { "%,d playable · %,d tags".format(it.playable, it.tags) } ?: "loading",
        )
        HairlineDivider()

        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, "https://cliamp.stream".toUri())
                        )
                    }
                }
                .padding(horizontal = Gutter, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_cliamp_logo),
                contentDescription = null,
                modifier = Modifier.size(34.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Mono("cliamp ${BuildConfig.VERSION_NAME} · all rights reserved", CliampType.rowSecondary, p.inkSecondary)
                Mono("cliamp.stream · radio-browser.info", CliampType.meta, p.inkFaint)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Mono(title, CliampType.rowPrimary, p.ink)
                Mono(subtitle, CliampType.rowSecondary, p.inkTertiary)
            }
            CliampToggle(checked, onChange)
        }
        Box(Modifier.padding(start = Gutter)) { HairlineDivider() }
    }
}

/**
 * A theme row that shows the theme rather than describing it: ground, accent,
 * ink and amber as swatches. A list of twenty-two names tells you nothing;
 * four squares tell you everything that matters at a glance.
 */
@Composable
private fun ThemeRow(
    key: String,
    theme: stream.cliamp.mobile.ui.theme.CliampPalette,
    selected: Boolean,
    subtitle: String? = null,
    onSelect: () -> Unit,
) {
    val p = LocalPalette.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(horizontal = Gutter, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // the swatch is the theme's own ground, so it reads as a chip
                // of that theme rather than of the current one
                Row(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(theme.ground)
                        .border(1.dp, theme.frameBorder, RoundedCornerShape(4.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    listOf(theme.accent, theme.ink, theme.amber).forEach { c ->
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(c))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Mono(
                        key.replace('-', ' '),
                        if (selected) CliampType.rowPrimaryMedium else CliampType.rowPrimary,
                        if (selected) p.accent else p.ink,
                        maxLines = 1,
                    )
                    Mono(subtitle ?: if (theme.dark) "dark" else "light", CliampType.meta, p.inkFaint)
                }
            }
            if (selected) Mono("ACTIVE", CliampType.tabLabel, p.accent)
        }
        Box(Modifier.padding(start = Gutter)) { HairlineDivider() }
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val p = LocalPalette.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono(title, CliampType.rowPrimary, p.ink)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                options.forEach { o -> Chip(o, selected == o, onClick = { onSelect(o) }) }
            }
        }
        Box(Modifier.padding(start = Gutter)) { HairlineDivider() }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    val p = LocalPalette.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Mono(title, CliampType.rowPrimary, p.ink)
            Mono(value, CliampType.rowSecondary, p.inkTertiary)
        }
        Box(Modifier.padding(start = Gutter)) { HairlineDivider() }
    }
}
