package stream.kleeamp.mobile.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.data.Prefs
import stream.kleeamp.mobile.data.Station
import stream.kleeamp.mobile.playback.EqPresets
import stream.kleeamp.mobile.playback.PlaybackBus
import stream.kleeamp.mobile.ui.components.BackChevron
import stream.kleeamp.mobile.ui.components.BrickMeter
import stream.kleeamp.mobile.ui.components.Chip
import stream.kleeamp.mobile.ui.components.KleeampToggle
import stream.kleeamp.mobile.ui.components.Gutter
import stream.kleeamp.mobile.ui.components.HairlineDivider
import stream.kleeamp.mobile.ui.components.MechSliderVertical
import stream.kleeamp.mobile.ui.components.MeterSize
import stream.kleeamp.mobile.ui.components.SectionLabel
import stream.kleeamp.mobile.ui.components.rememberMeter
import stream.kleeamp.mobile.ui.theme.KleeampType
import stream.kleeamp.mobile.ui.theme.LocalPalette
import stream.kleeamp.mobile.ui.theme.Mono

private val bandLabels = listOf("60", "150", "400", "1k", "3k", "8k", "16k")
private val rulerLabels = listOf("32", "125", "500", "2k", "8k", "20k")

@Composable
fun ScopeScreen(
    prefs: Prefs,
    station: Station?,
    streamTitle: String,
    playing: Boolean,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()

    val spectrum = PlaybackBus.spectrum.collectAsState()
    val visualizer by prefs.visualizer.collectAsState(initial = "spectrum")
    val spectrumLive by PlaybackBus.spectrumLive.collectAsState()
    val eqEnabled by prefs.eqEnabled.collectAsState(initial = false)
    val eqBands by prefs.eqBands.collectAsState(initial = List(7) { 0f })
    val eqPreset by prefs.eqPreset.collectAsState(initial = "flat")

    Column(
        Modifier
            .fillMaxSize()
            .background(p.groundScope)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackChevron(onBack)
            Mono(
                when {
                    visualizer == "off" -> "VISUALIZER OFF"
                    spectrumLive -> "SPECTRUM · LIVE"
                    playing -> "SPECTRUM · SIMULATED"
                    else -> "SPECTRUM · IDLE"
                },
                KleeampType.sectionLabel,
                if (spectrumLive) p.accent else p.inkTertiary,
            )
        }

        // Explicit visualizer switch: spectrum or off, same setting the
        // player and mini player read. Sits above the meter it controls.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono("Visualizer", KleeampType.rowPrimaryMedium, p.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(
                    "spectrum",
                    visualizer == "spectrum",
                    onClick = { scope.launch { prefs.setVisualizer("spectrum") } },
                )
                Chip(
                    "off",
                    visualizer == "off",
                    onClick = { scope.launch { prefs.setVisualizer("off") } },
                )
            }
        }

        // The meter is the visualizer: when the setting is off it is removed
        // entirely (no frame loop, no grid, no peak readout), leaving just the
        // equalizer on this screen.
        if (visualizer != "off") {
            val frame = rememberMeter(
                columns = MeterSize.Scope.columns,
                live = playing,
                spectrum = spectrum,
            )

            // Peak level in dBFS, read straight off the folded spectrum.
            val peakDb = spectrum.value.maxOrNull()?.let { -48f + it * 48f } ?: -48f

            Column(Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
                // The peak datum breathes with the meter while the spectrum is
                // live, quieting to a solid value the moment it is not.
                val peakTransition = rememberInfiniteTransition(label = "peak")
                val peakAlpha by peakTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "peakAlpha",
                )
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Mono("PEAK", KleeampType.sectionLabel, p.inkFaint)
                    Mono(
                        "%.0f dB".format(peakDb.coerceIn(-48f, 0f)),
                        KleeampType.datum,
                        p.accent.copy(alpha = if (spectrumLive) peakAlpha else 1f),
                    )
                }
                BrickMeter(
                    frame = frame,
                    modifier = Modifier.fillMaxWidth().height(MeterSize.Scope.height),
                    brick = MeterSize.Scope.brick,
                    gap = MeterSize.Scope.gap,
                    columnGap = 3.dp,
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                rulerLabels.forEach { Mono(it, KleeampType.meta, p.inkFaint) }
            }
        }

        HairlineDivider(region = true)

        Column(Modifier.padding(horizontal = Gutter, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Mono(station?.name ?: "nothing tuned", KleeampType.trackTitleSmall, p.ink, maxLines = 1)
            Mono(
                streamTitle.ifBlank { station?.meta ?: "" },
                KleeampType.rowSecondary,
                p.inkTertiary,
                maxLines = 1,
            )
        }

        HairlineDivider(region = true)

        SectionLabel("equalizer — 7 band") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Mono(eqPreset, KleeampType.meta, p.inkSecondary)
                KleeampToggle(eqEnabled, onChange = { scope.launch { prefs.setEqEnabled(it) } })
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            bandLabels.forEachIndexed { i, label ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MechSliderVertical(
                        value = eqBands.getOrElse(i) { 0f },
                        onValueChange = { v ->
                            scope.launch {
                                val next = eqBands.toMutableList().also { it[i] = v }
                                prefs.setEqBands(next)
                                prefs.setEqPreset("custom")
                                if (!eqEnabled) prefs.setEqEnabled(true)
                            }
                        },
                        modifier = Modifier.height(150.dp),
                    )
                    Mono(label, KleeampType.meta, p.inkTertiary)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            EqPresets.names.forEach { name ->
                Chip(name, eqPreset == name, onClick = {
                    scope.launch {
                        EqPresets.byName(name)?.let { prefs.setEqBands(it) }
                        prefs.setEqPreset(name)
                        prefs.setEqEnabled(true)
                    }
                })
            }
            Chip("reset", false, onClick = {
                scope.launch {
                    prefs.setEqBands(EqPresets.flat)
                    prefs.setEqPreset("flat")
                    prefs.setEqEnabled(false)
                }
            })
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp)) {
            Mono(
                "eq and spectrum attach to the decoder output. they need the record-audio permission, " +
                    "which android uses to gate the visualizer api even with no microphone involved",
                KleeampType.meta,
                p.inkFaint,
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}
