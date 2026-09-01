package stream.cliamp.mobile.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.DirectoryQuery
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.Repository
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.playback.PlaybackBus
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.ScreenHeader
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalHapticsEnabled
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

private enum class Scope(val label: String) { All("all"), Stations("stations"), Tags("tags"), Cmds("cmds") }

private data class Command(
    val syntax: String,
    val hint: String,
    val takesArg: Boolean = false,
)

private val commands = listOf(
    Command(":play", "tune the first hit for a name", takesArg = true),
    Command(":tag", "filter the directory by tag", takesArg = true),
    Command(":country", "filter the directory by iso code", takesArg = true),
    Command(":random", "tune anything at all"),
    Command(":fav", "star what is playing"),
    Command(":stop", "drop the stream"),
    Command(":scope", "open scope and eq"),
    Command(":eq", "flat | rock | headphone", takesArg = true),
    Command(":stats", "cliamp radio listeners"),
    Command(":settings", "playback, feel, storage"),
    Command(":clear", "empty the bar"),
)

/**
 * The command bar owns its own keyboard. That is not a stylistic flourish: a
 * system IME would cover half the screen with a different type family and
 * break the frame budget, and the concept's key caps are the same mechanical
 * component used by the transport.
 */
@Composable
fun CommandScreen(
    repository: Repository,
    prefs: Prefs,
    onPlay: (Station, List<Station>) -> Unit,
    onOpenScope: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Scope.All) }
    var shift by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    val directory by repository.directory.collectAsState()
    val cliamp by repository.cliamp.collectAsState()
    val tags by repository.tags.collectAsState()

    val isCommand = query.startsWith(":")
    val term = query.trim()

    // Debounce: the directory is somebody else's server, not ours.
    LaunchedEffect(term) {
        if (isCommand || term.length < 2) return@LaunchedEffect
        delay(320)
        repository.loadDirectory(DirectoryQuery.Search(term), reset = true)
    }

    val localHits = remember(term, cliamp) {
        if (term.isBlank()) emptyList()
        else cliamp.filter { it.name.contains(term, ignoreCase = true) }
    }
    val cmdHits = remember(query) {
        if (!isCommand) emptyList()
        else commands.filter { it.syntax.startsWith(query.substringBefore(' '), ignoreCase = true) }
    }
    val tagHits = remember(term, tags) {
        if (term.isBlank()) emptyList()
        else tags.filter { it.name.contains(term, ignoreCase = true) }.take(12)
    }
    val stationHits = localHits + directory.stations
    val total = cmdHits.size + stationHits.size + tagHits.size

    fun run(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val verb = text.substringBefore(' ')
        val arg = text.substringAfter(' ', "").trim()
        note = when (verb) {
            ":play" -> {
                val hit = (localHits + directory.stations)
                    .firstOrNull { arg.isBlank() || it.name.contains(arg, ignoreCase = true) }
                if (hit != null) {
                    onPlay(hit, stationHits); onOpenPlayer(); null
                } else "no station matches \"$arg\""
            }
            ":tag" -> {
                repository.loadDirectory(DirectoryQuery.Tag(arg), reset = true)
                filter = Scope.Stations
                "directory filtered to #$arg"
            }
            ":country" -> {
                repository.loadDirectory(DirectoryQuery.Country(arg.uppercase(), arg.uppercase()), reset = true)
                filter = Scope.Stations
                "directory filtered to ${arg.uppercase()}"
            }
            ":random" -> {
                val pool = directory.stations.ifEmpty { cliamp }
                pool.randomOrNull()?.let { onPlay(it, pool); onOpenPlayer() }
                null
            }
            ":fav" -> {
                PlaybackBus.station.value?.let { s -> scope.launch { prefs.toggleFavorite(s) } }
                "toggled favourite"
            }
            ":stop" -> { scope.launch { }; "use the transport key to stop" }
            ":scope" -> { onOpenScope(); null }
            ":stats" -> { onOpenStats(); null }
            ":settings" -> { onOpenSettings(); null }
            ":eq" -> { scope.launch { prefs.setEqPreset(arg.ifBlank { "flat" }); prefs.setEqEnabled(true) }; "eq → ${arg.ifBlank { "flat" }}" }
            ":clear" -> { query = ""; null }
            else -> {
                repository.loadDirectory(DirectoryQuery.Search(text), reset = true)
                null
            }
        }
        if (verb != ":clear" && !verb.startsWith(":")) query = text
    }

    Column(Modifier.fillMaxSize().background(p.ground)) {
        ScreenHeader(divider = false) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gutter)
                    .padding(top = 10.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mono(":", CliampType.trackTitleCompact, p.accent)
                Spacer(Modifier.width(6.dp))
                Mono(
                    query.removePrefix(":"),
                    CliampType.trackTitleCompact,
                    p.ink,
                    maxLines = 1,
                )
                BlinkingCursor()
                Spacer(Modifier.weight(1f))
                Mono(
                    if (query.isBlank()) "type to search" else "$total hits",
                    CliampType.rowSecondary,
                    p.inkTertiary,
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(start = Gutter, end = Gutter, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Scope.entries.forEach { s -> Chip(s.label, filter == s, onClick = { filter = s }) }
            }
            HairlineDivider(region = true)
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            note?.let {
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp)) {
                        Mono(it, CliampType.rowSecondary, p.amber)
                    }
                }
            }

            if (filter == Scope.All || filter == Scope.Cmds) {
                val shown = if (query.isBlank()) commands else cmdHits
                if (shown.isNotEmpty()) {
                    item { SectionLabel("commands") }
                    items(shown, key = { "cmd:${it.syntax}" }) { c ->
                        ListRow(
                            onClick = { query = c.syntax + if (c.takesArg) " " else ""; if (!c.takesArg) run(c.syntax) },
                            verticalPadding = 11.dp,
                            leading = { Icon(CliampIcons.CmdSmall, null, Modifier.size(15.dp), tint = p.accent) },
                            trailing = { Mono("⏎", CliampType.rowSecondary, p.inkFaint) },
                        ) {
                            Mono(c.syntax + if (c.takesArg) " …" else "", CliampType.rowPrimary, p.ink, maxLines = 1)
                            Mono(c.hint, CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
                        }
                    }
                }
            }

            if ((filter == Scope.All || filter == Scope.Tags) && tagHits.isNotEmpty()) {
                item { SectionLabel("tags — ${tagHits.size}") }
                items(tagHits, key = { "tag:${it.name}" }) { t ->
                    ListRow(
                        onClick = {
                            repository.loadDirectory(DirectoryQuery.Tag(t.name), reset = true)
                            filter = Scope.Stations
                        },
                        verticalPadding = 10.dp,
                        leading = { Icon(CliampIcons.ListShort, null, Modifier.size(14.dp), tint = p.inkTertiary) },
                        trailing = { Mono("${t.stationcount}", CliampType.meta, p.inkFaint) },
                    ) {
                        Mono("#${t.name}", CliampType.rowPrimary, p.ink, maxLines = 1)
                    }
                }
            }

            if (filter == Scope.All || filter == Scope.Stations) {
                if (stationHits.isNotEmpty()) {
                    item { SectionLabel("stations — ${stationHits.size}") }
                    itemsIndexed(stationHits.take(200), key = { _, it -> "hit:${it.url}" }) { i, s ->
                        val index = i + 1
                        ListRow(
                            onClick = { onPlay(s, stationHits); onOpenPlayer() },
                            verticalPadding = 10.dp,
                            leading = {
                                Mono("%02d".format(index.coerceAtMost(99)), CliampType.rowSecondary, p.inkFaint)
                            },
                            trailing = {
                                Mono(
                                    if (s.source == StationSource.Cliamp) "cliamp" else s.countryCode.lowercase(),
                                    CliampType.meta,
                                    if (s.source == StationSource.Cliamp) p.accent else p.inkFaint,
                                )
                            },
                        ) {
                            Mono(s.name, CliampType.rowPrimary, p.ink, maxLines = 1)
                            Mono(s.meta, CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
                        }
                    }
                } else if (query.isNotBlank() && !isCommand) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 18.dp)) {
                            Mono(
                                if (directory.loading) "searching…" else "no hits for \"$term\"",
                                CliampType.rowSecondary,
                                p.inkFaint,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }

        Keyboard(
            shift = shift,
            onKey = { c -> query += if (shift) c.uppercase() else c; shift = false },
            onShift = { shift = !shift },
            onBackspace = { query = query.dropLast(1) },
            onColon = { query = if (query.startsWith(":")) query else ":$query" },
            onRun = { run(query) },
        )
    }
}

@Composable
private fun BlinkingCursor() {
    val p = LocalPalette.current
    val t = rememberInfiniteTransition(label = "cursor")
    val a by t.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(560), RepeatMode.Reverse),
        label = "cursorAlpha",
    )
    Box(
        Modifier
            .padding(start = 2.dp)
            .size(width = 10.dp, height = 18.dp)
            .alpha(if (a > 0.5f) 1f else 0f)
            .background(p.accent)
    )
}

private val rows = listOf(
    "qwertyuiop".toList(),
    "asdfghjkl".toList(),
    "zxcvbnm".toList(),
)

@Composable
private fun Keyboard(
    shift: Boolean,
    onKey: (String) -> Unit,
    onShift: () -> Unit,
    onBackspace: () -> Unit,
    onColon: () -> Unit,
    onRun: () -> Unit,
) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (p.dark) p.panel else p.panel)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEachIndexed { i, row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (i == 2) {
                    Cap("⇧", Modifier.weight(1.4f), active = shift, onClick = onShift)
                }
                row.forEach { c ->
                    Cap(
                        if (shift) c.uppercase() else c.toString(),
                        Modifier.weight(1f),
                        onClick = { onKey(c.toString()) },
                    )
                }
                if (i == 2) {
                    Cap("⌫", Modifier.weight(1.4f), onClick = onBackspace)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Cap(":cmd", Modifier.weight(1.6f), onClick = onColon)
            Cap("space", Modifier.weight(3f), onClick = { onKey(" ") })
            Cap("-", Modifier.weight(1f), onClick = { onKey("-") })
            Cap("RUN ⏎", Modifier.weight(2f), filled = true, onClick = onRun)
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun Cap(
    label: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val hf = LocalHapticFeedback.current
    val hapticsOn = LocalHapticsEnabled.current
    val face = when {
        filled && p.dark -> p.accent
        filled -> p.ink
        active -> p.accentWash
        p.dark -> p.keyFace
        else -> p.ground
    }
    val fg = when {
        filled && p.dark -> p.onAccent
        filled -> p.ground
        active -> p.accent
        else -> p.ink
    }
    Box(
        modifier
            .height(42.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(face)
            .then(if (!filled) Modifier.border(1.dp, p.keyBorder, RoundedCornerShape(6.dp)) else Modifier)
            .clickable {
                if (hapticsOn) hf.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Mono(label, CliampType.keyCap, fg, maxLines = 1)
    }
}
