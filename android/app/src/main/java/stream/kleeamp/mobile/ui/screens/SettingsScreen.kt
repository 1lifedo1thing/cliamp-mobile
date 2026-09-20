package stream.kleeamp.mobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import stream.kleeamp.mobile.BuildConfig
import stream.kleeamp.mobile.R
import stream.kleeamp.mobile.data.visualizer.Visualizer
import stream.kleeamp.mobile.ui.components.Chip
import stream.kleeamp.mobile.ui.components.KleeampIcons
import stream.kleeamp.mobile.ui.components.KleeampToggle
import stream.kleeamp.mobile.ui.components.Gutter
import stream.kleeamp.mobile.ui.components.scrollToTop
import stream.kleeamp.mobile.ui.components.HairlineDivider
import stream.kleeamp.mobile.ui.components.MainLayout
import stream.kleeamp.mobile.ui.components.MechSlider
import stream.kleeamp.mobile.ui.components.microPress
import stream.kleeamp.mobile.ui.components.SectionLabel
import stream.kleeamp.mobile.ui.theme.AmberPalette
import stream.kleeamp.mobile.ui.theme.KleeampShape
import stream.kleeamp.mobile.ui.theme.KleeampType
import stream.kleeamp.mobile.ui.theme.DarkPalette
import stream.kleeamp.mobile.ui.theme.LightPalette
import stream.kleeamp.mobile.ui.theme.LocalPalette
import stream.kleeamp.mobile.ui.theme.OmarchyPalettes
import stream.kleeamp.mobile.ui.theme.OxideLightPalette
import stream.kleeamp.mobile.ui.theme.OxidePalette
import stream.kleeamp.mobile.ui.theme.OmarchyThemeKeys
import stream.kleeamp.mobile.ui.theme.Mono
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenScrobble: () -> Unit = {},
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val uiState by vm.state.collectAsState()
    val palette = uiState.palette
    val custom = uiState.custom
    val customName = uiState.customName
    val importError = uiState.importError
    val haptics = uiState.haptics
    val visualizer = uiState.visualizer
    val cellular = uiState.cellular
    val mono = uiState.mono
    val buffer = uiState.buffer
    val autoResume = uiState.autoResume
    val autoDownload = uiState.autoDownload
    val resumeLocal = uiState.resumeLocal
    val listenBrainzOn = uiState.listenBrainzOn
    val dirStats = uiState.directoryStats
    val themeImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.take(256 * 1024)?.toByteArray()?.decodeToString()
        }.getOrNull()
        vm.onEvent(SettingsViewModel.Event.ImportTheme(raw))
    }

    val scrollState = rememberScrollState()
    MainLayout(
        title = "Settings",
        onOpenSearch = onOpenSearch,
        onOpenSettings = null,
        onBack = onBack,
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
            onChange = { vm.onEvent(SettingsViewModel.Event.SetAutoResume(it)) },
        )
        ToggleRow(
            title = "Resume local songs",
            checked = resumeLocal,
            onChange = { vm.onEvent(SettingsViewModel.Event.SetResumeLocal(it)) },
        )
        ToggleRow(
            title = "Auto-download episodes",
            checked = autoDownload,
            onChange = { vm.onEvent(SettingsViewModel.Event.SetAutoDownload(it)) },
        )
        ToggleRow(
            title = "Stream over cellular",
            checked = cellular,
            onChange = { vm.onEvent(SettingsViewModel.Event.SetCellular(it)) },
        )
        ToggleRow(
            title = "Mono downmix",
            checked = mono,
            onChange = { vm.onEvent(SettingsViewModel.Event.SetMono(it)) },
        )
        Column(Modifier.padding(horizontal = Gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Mono("Buffer", KleeampType.rowPrimary, p.ink)
                Mono("${buffer}s", KleeampType.rowSecondary, p.accent)
            }
            MechSlider(
                value = buffer.toFloat(),
                onValueChange = { vm.onEvent(SettingsViewModel.Event.SetBuffer(it.roundToInt())) },
                range = 5f..60f,
                modifier = Modifier.fillMaxWidth(),
            )
            Mono("deeper buffers survive a bad tunnel, at the cost of latency", KleeampType.meta, p.inkFaint)
        }
        HairlineDivider()

        SectionLabel("scrobble")
        Row(
            Modifier.fillMaxWidth().microPress { onOpenScrobble() }
                .padding(horizontal = Gutter, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono("ListenBrainz", KleeampType.rowPrimaryMedium, p.ink)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mono(
                    if (listenBrainzOn) "on" else "off",
                    KleeampType.meta, if (listenBrainzOn) p.accent else p.inkFaint,
                )
                Icon(KleeampIcons.CaretRight, "open", Modifier.size(11.dp), tint = p.inkTertiary)
            }
        }
        HairlineDivider()

        SectionLabel("feel")
        ToggleRow(
            title = "Key haptics",
            checked = haptics,
            onChange = { vm.onEvent(SettingsViewModel.Event.SetHaptics(it)) },
        )
        ChoiceRow(
            title = "Visualizer",
            options = Visualizer.selectable.map { it.id to it.label } + listOf("off" to "off"),
            selected = visualizer,
            onSelect = { vm.onEvent(SettingsViewModel.Event.SetVisualizer(it)) },
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
                onSelect = { vm.onEvent(SettingsViewModel.Event.SetPalette(key)) },
            )
        }
        OmarchyThemeKeys.forEachIndexed { index, key ->
            ThemeRow(
                key = key,
                theme = OmarchyPalettes.getValue(key),
                selected = palette == key,
                // Last row before the next section: its divider runs full
                // width instead of stacking a second inset line under it.
                trailDivider = index != OmarchyThemeKeys.lastIndex || custom != null,
                onSelect = { vm.onEvent(SettingsViewModel.Event.SetPalette(key)) },
            )
        }
        AnimatedVisibility(
            visible = custom != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            if (custom != null) {
                UpNextSwipeToRemove(
                    onRemove = { vm.onEvent(SettingsViewModel.Event.ClearCustomTheme) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Opaque like the Up Next rows: the red must only show
                    // where the drag uncovers, never through the row itself.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(p.ground)
                    ) {
                        ThemeRow(
                            key = customName ?: "custom",
                            theme = custom,
                            selected = palette == "custom",
                            trailDivider = false,
                            onSelect = { vm.onEvent(SettingsViewModel.Event.SetPalette("custom")) },
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().microPress { themeImporter.launch("application/json") }
                .padding(horizontal = Gutter, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono("Import theme file", KleeampType.rowPrimaryMedium, p.ink)
            Mono("json", KleeampType.meta, p.inkFaint)
        }
        importError?.let {
            Mono(
                it, KleeampType.rowSecondary, p.destructiveInk,
                Modifier.padding(start = Gutter, end = Gutter, bottom = 12.dp),
            )
        }

        SectionLabel("storage")
        InfoRow("Favourites", "${uiState.favoritesCount} stations")
        InfoRow("History", "${uiState.historyCount} entries")
        Row(
            Modifier
                .fillMaxWidth()
                .microPress { vm.onEvent(SettingsViewModel.Event.ClearHistory) }
                .padding(horizontal = Gutter, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono("Purge history", KleeampType.rowPrimary, p.destructiveInk)
            Mono("▸", KleeampType.rowPrimary, p.destructiveInk)
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
                painter = painterResource(R.drawable.ic_kleeamp_logo),
                contentDescription = null,
                modifier = Modifier.size(34.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // A release carries the tag it was built from; a local debug
                // build is not a release and must not name one.
                val version = if (BuildConfig.DEBUG) "dev" else BuildConfig.VERSION_NAME
                Mono("kleeamp $version · all rights reserved", KleeampType.rowSecondary, p.inkSecondary)
                Mono("cliamp.stream", KleeampType.meta, p.inkFaint)
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
            Mono(title, KleeampType.rowPrimaryMedium, p.ink, Modifier.weight(1f))
            KleeampToggle(checked, onChange)
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
    theme: stream.kleeamp.mobile.ui.theme.KleeampPalette,
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
                        .clip(RoundedCornerShape(KleeampShape.tiny))
                        .background(theme.ground)
                        .border(1.dp, theme.frameBorder, RoundedCornerShape(KleeampShape.tiny))
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
                        if (selected) KleeampType.rowPrimaryMedium else KleeampType.rowPrimary,
                        if (selected) p.accent else p.ink,
                        maxLines = 1,
                    )
                    Mono(subtitle ?: if (theme.dark) "dark" else "light", KleeampType.meta, p.inkFaint)
                }
            }
            if (selected) Mono("ACTIVE", KleeampType.tabLabel, p.accent)
        }
        if (trailDivider) {
            Box(Modifier.padding(start = Gutter)) { HairlineDivider() }
        } else {
            HairlineDivider()
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val p = LocalPalette.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono(title, KleeampType.rowPrimary, p.ink)
            // Fixed gap: the scrollable chips row overflows the row width,
            // so SpaceBetween alone leaves the title touching the first chip.
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                options.forEach { (id, label) ->
                    Chip(label, selected == id, onClick = { onSelect(id) })
                }
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
            Mono(title, KleeampType.rowPrimary, p.ink)
            Mono(value, KleeampType.rowSecondary, p.inkTertiary)
        }
        Box(Modifier.padding(start = Gutter)) { HairlineDivider() }
    }
}
