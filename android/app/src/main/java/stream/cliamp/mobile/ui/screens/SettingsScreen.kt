package stream.cliamp.mobile.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import stream.cliamp.mobile.ui.components.BackChip
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.CliampToggle
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.scrollToTop
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.MainLayout
import stream.cliamp.mobile.ui.components.MechSlider
import stream.cliamp.mobile.ui.components.microPress
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.theme.AmberPalette
import stream.cliamp.mobile.ui.theme.CliampShape
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
    onOpenSearch: () -> Unit = {},
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

    val scrollState = rememberScrollState()
    MainLayout(
        title = "Settings",
        onOpenSearch = onOpenSearch,
        onOpenSettings = null,
        chips = {
            BackChip(onClick = onBack)
        },
        onTitleClick = { scope.scrollToTop(scrollState) },
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
        ) {
            SectionLabel("playback")
        ToggleRow(
            title = "Auto-resume",
            checked = autoResume,
            onChange = { scope.launch { prefs.setAutoResume(it) } },
        )
        ToggleRow(
            title = "Stream over cellular",
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
            checked = haptics,
            onChange = { scope.launch { prefs.setHaptics(it) } },
        )
        ChoiceRow(
            title = "Visualizer",
            options = listOf("spectrum", "off"),
            selected = visualizer,
            onSelect = { scope.launch { prefs.setVisualizer(it) } },
        )

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
        OmarchyThemeKeys.forEachIndexed { index, key ->
            ThemeRow(
                key = key,
                theme = OmarchyPalettes.getValue(key),
                selected = palette == key,
                // Last row before the next section: its divider runs full
                // width instead of stacking a second inset line under it.
                trailDivider = index != OmarchyThemeKeys.lastIndex,
                onSelect = { scope.launch { prefs.setPalette(key) } },
            )
        }

        SectionLabel("storage")
        InfoRow("Favourites", "${favorites.size} stations")
        InfoRow("History", "${history.size} entries")
        Row(
            Modifier
                .fillMaxWidth()
                .microPress { scope.launch { prefs.clearHistory() } }
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
                .microPress {
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
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono(title, CliampType.rowPrimaryMedium, p.ink, Modifier.weight(1f))
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
    // False on the last row before a section break: the divider runs full
    // width instead of stacking a second inset line under it.
    trailDivider: Boolean = true,
    onSelect: () -> Unit,
) {
    val p = LocalPalette.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .microPress(onClick = onSelect)
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
                        .clip(RoundedCornerShape(CliampShape.tiny))
                        .background(theme.ground)
                        .border(1.dp, theme.frameBorder, RoundedCornerShape(CliampShape.tiny))
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
        if (trailDivider) {
            Box(Modifier.padding(start = Gutter)) { HairlineDivider() }
        } else {
            HairlineDivider()
        }
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
        // Last row before the themes section: full width instead of stacking
        // a second inset line under it.
        HairlineDivider()
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
