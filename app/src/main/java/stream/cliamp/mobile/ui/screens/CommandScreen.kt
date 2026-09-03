package stream.cliamp.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.DirectoryQuery
import stream.cliamp.mobile.data.LocalLibrary
import stream.cliamp.mobile.data.PodcastQuery
import stream.cliamp.mobile.data.PodcastShow
import stream.cliamp.mobile.data.PodcastRepository
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.Repository
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationArtSource
import stream.cliamp.mobile.data.provider.ProviderAccount
import stream.cliamp.mobile.data.provider.ProviderStore
import stream.cliamp.mobile.playback.PlaybackBus
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.CliampTextField
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.ScreenHeader
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.search.Fuzzy
import stream.cliamp.mobile.ui.search.GlobalSearch
import stream.cliamp.mobile.ui.search.SearchHit
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalHapticsEnabled
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

private enum class Scope(val label: String) {
    All("all"), Media("local"), Radio("radio"), Pods("podcasts"),
    Tags("tags"), Providers("providers"), Cmds("cmds"),
}

/**
 * The command bar owns its own keyboard. That is not a stylistic flourish: a
 * system IME would cover half the screen with a different type family and
 * break the frame budget, and the concept's key caps are the same mechanical
 * component used by the transport. It doubles as the app-wide fuzzy finder:
 * one query spans local songs, favourites, the radio directory and providers.
 */
@Composable
fun CommandScreen(
    repository: Repository,
    podcasts: PodcastRepository,
    prefs: Prefs,
    localLibrary: LocalLibrary,
    providers: ProviderStore,
    onPlay: (Station, List<Station>) -> Unit,
    onOpenScope: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProvider: (ProviderAccount) -> Unit,
    onOpenShow: (PodcastShow) -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Scope.All) }

    val directory by repository.directory.collectAsState()
    val cliamp by repository.cliamp.collectAsState()
    val tags by repository.tags.collectAsState()
    val songs by localLibrary.songs.collectAsState()
    val favorites by prefs.favorites.collectAsState(initial = emptyList())
    val recent by prefs.history.collectAsState(initial = emptyList())
    val providerAccounts by providers.accounts.collectAsState(initial = emptyList())

    val radio = remember(cliamp, directory) {
        cliamp + directory.stations
    }

    val isCommand = query.startsWith(":")
    val term = query.trim()

    // Debounce the directory: it is somebody else's server, not ours. The local
    // and radio fuzzy pass runs instantly on what we already hold.
    LaunchedEffect(term) {
        if (isCommand || term.length < 2) return@LaunchedEffect
        delay(320)
        repository.loadDirectory(DirectoryQuery.Search(term), reset = true)
        // The podcast directory takes the same query, so a show can be found
        // by name. Unlike the Stations tab, the Podcasts tab keeps the search
        // rather than resetting it: Apple's search returns whole shows in one
        // request with nothing to page, so the searched list IS the directory
        // for as long as the query stands, and its header names the query.
        podcasts.load(PodcastQuery.Search(term), reset = true)
    }

    val podcastDirectory by podcasts.directory.collectAsState()
    val subscriptions by podcasts.subscriptions.collectAsState(initial = emptyList())

    // Subscriptions are resident, the directory half is whatever the debounced
    // query above just fetched, so a show can be found whether or not it is
    // already followed.
    val shows = remember(subscriptions, podcastDirectory.shows) {
        subscriptions + podcastDirectory.shows
    }
    val subscribedFeeds = remember(subscriptions) { subscriptions.mapTo(HashSet()) { it.feedUrl } }

    val results = remember(
        term, filter, songs, favorites, recent, radio, tags, providerAccounts, shows, subscribedFeeds,
    ) {
        GlobalSearch.run(
            term, songs, favorites, recent, radio, tags, providerAccounts, shows, subscribedFeeds,
        )
    }.hits

    val shown = when (filter) {
        Scope.All -> results
        Scope.Media -> results.filter { it is SearchHit.Song || it is SearchHit.Favorite }
        Scope.Radio -> results.filter { it is SearchHit.StationHit }
        Scope.Pods -> results.filter { it is SearchHit.Show }
        Scope.Tags -> results.filter { it is SearchHit.Tag }
        Scope.Providers -> results.filter { it is SearchHit.Provider }
        Scope.Cmds -> results.filter { it is SearchHit.Command }
    }

    fun run(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val verb = text.substringBefore(' ')
        val arg = text.substringAfter(' ', "").trim()
        when (verb) {
            ":play" -> {
                val hit = results.firstOrNull {
                    it.playable != null && (arg.isBlank() || it.haystack.contains(arg, ignoreCase = true))
                } ?: GlobalSearch.run(arg, songs, favorites, recent, radio, tags, providerAccounts).hits
                    .firstOrNull { it.playable != null }
                hit?.playable?.let { onPlay(it, listOf(it)) }
            }
            ":tag" -> repository.loadDirectory(DirectoryQuery.Tag(arg), reset = true)
            ":country" -> repository.loadDirectory(DirectoryQuery.Country(arg.uppercase(), arg.uppercase()), reset = true)
            ":random" -> {
                val pool = directory.stations.ifEmpty { cliamp }
                pool.randomOrNull()?.let { onPlay(it, pool) }
            }
            ":fav" -> PlaybackBus.station.value?.let { s -> scope.launch { prefs.toggleFavorite(s) } }
            ":scope" -> onOpenScope()
            ":settings" -> onOpenSettings()
            ":eq" -> scope.launch {
                prefs.setEqPreset(arg.ifBlank { "flat" }); prefs.setEqEnabled(true)
            }
            ":clear" -> query = ""
            else -> repository.loadDirectory(DirectoryQuery.Search(text), reset = true)
        }
        if (verb != ":clear" && !verb.startsWith(":")) query = text
    }

    fun open(hit: SearchHit, queue: List<SearchHit>) {
        when (hit) {
            is SearchHit.Provider -> onOpenProvider(hit.account)
            is SearchHit.Show -> onOpenShow(hit.show)
            is SearchHit.Tag -> repository.loadDirectory(DirectoryQuery.Tag(hit.name), reset = true)
            else -> hit.playable?.let { s ->
                onPlay(s, queue.mapNotNull { it.playable })
            }
        }
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
                CliampTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    placeholder = "Search",
                    imeAction = ImeAction.Go,
                    onAction = { run(query) },
                    autoFocus = true,
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

        if (results.isEmpty() && term.isNotBlank()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 20.dp)) {
                Mono("no hits anywhere for \"$term\"", CliampType.rowSecondary, p.inkFaint)
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isNotEmpty()) {
                val first = shown.first()
                val label = sectionOf(first)
                item(key = "label:$label") { SectionLabel(label) }
                items(shown, key = { it.key }) { hit ->
                    HitRow(
                        hit = hit,
                        term = term,
                        onClick = { open(hit, shown) },
                        accent = p.accent,
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

private fun sectionOf(hit: SearchHit): String {
    val head = when (hit) {
        is SearchHit.Song, is SearchHit.Favorite -> "local"
        is SearchHit.StationHit -> "radio"
        is SearchHit.Show -> "podcasts"
        is SearchHit.Tag -> "tags"
        is SearchHit.Provider -> "providers"
        is SearchHit.Command -> "commands"
    }
    return if (hit is SearchHit.Tag) "tags — global" else "$head — global"
}

@Composable
private fun HitArt(hit: SearchHit, accent: androidx.compose.ui.graphics.Color) {
    val p = LocalPalette.current
    // Commands, tags and provider rows carry no art; the type glyph is right.
    val station = when (hit) {
        is SearchHit.Song -> hit.station
        is SearchHit.Favorite -> hit.station
        is SearchHit.StationHit -> hit.station
        else -> null
    }
    if (station == null) {
        Icon(
            iconOf(hit), null, Modifier.size(15.dp),
            tint = if (hit is SearchHit.Command) accent else p.inkTertiary,
        )
        return
    }

    var art by remember(station.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(station.id) {
        delay(90)
        art = StationArtSource.bitmapForSmall(station)?.asImageBitmap()
    }
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(5.dp))
            .then(
                if (art != null) Modifier.background(p.panel)
                else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(5.dp))
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art!!, station.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(iconOf(hit), null, Modifier.size(15.dp), tint = p.inkTertiary)
        }
    }
}

@Composable
private fun HitRow(
    hit: SearchHit,
    term: String,
    onClick: () -> Unit,
    accent: androidx.compose.ui.graphics.Color,
) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val enabled = LocalHapticsEnabled.current
    val fuzzTerm = if (term.startsWith(":")) "" else term

    ListRow(
        onClick = {
            if (enabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        verticalPadding = 10.dp,
        leading = { HitArt(hit, accent) },
        trailing = { Mono(hit.origin, CliampType.meta, p.inkFaint) },
    ) {
        val title = when (hit) {
            is SearchHit.Song -> hit.station.name
            is SearchHit.Favorite -> hit.station.name
            is SearchHit.StationHit -> hit.station.name
            is SearchHit.Show -> hit.show.title
            is SearchHit.Tag -> "#${hit.name}"
            is SearchHit.Provider -> hit.account.label
            is SearchHit.Command -> hit.syntax + if (hit.takesArg) " …" else ""
        }
        val sub = when (hit) {
            is SearchHit.Song -> hit.station.artist
            is SearchHit.Favorite -> hit.station.meta.ifBlank { hit.station.name }
            is SearchHit.StationHit -> hit.station.meta
            is SearchHit.Show -> hit.show.meta
            is SearchHit.Tag -> "${hit.count} stations"
            is SearchHit.Provider -> hit.specLabel
            is SearchHit.Command -> hit.hint
        }
        androidx.compose.material3.Text(
            text = highlight(title, fuzzTerm, accent),
            style = CliampType.rowPrimary.copy(color = p.ink),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (sub.isNotBlank()) {
            Mono(sub, CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
        }
    }
}

private fun highlight(haystack: String, term: String, accent: androidx.compose.ui.graphics.Color): AnnotatedString {
    if (term.isBlank()) return AnnotatedString(haystack)
    val at = Fuzzy.matchedPositions(term, haystack) ?: return AnnotatedString(haystack)
    return buildAnnotatedString {
        haystack.forEachIndexed { i, c ->
            if (i in at) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) { append(c) }
            else append(c)
        }
    }
}

private fun iconOf(hit: SearchHit): ImageVector = when (hit) {
    is SearchHit.Song, is SearchHit.Favorite -> CliampIcons.MusicNote
    is SearchHit.StationHit -> CliampIcons.StationsTab
    is SearchHit.Show -> CliampIcons.PodRow
    is SearchHit.Tag -> CliampIcons.ListShort
    is SearchHit.Provider -> CliampIcons.Search
    is SearchHit.Command -> CliampIcons.CmdSmall
}