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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import stream.cliamp.mobile.data.DirectoryQuery
import stream.cliamp.mobile.data.LocalLibrary
import stream.cliamp.mobile.data.PodcastDirectory
import stream.cliamp.mobile.data.PodcastEpisode
import stream.cliamp.mobile.data.PodcastShow
import stream.cliamp.mobile.data.PodcastRepository
import stream.cliamp.mobile.data.Prefs
import stream.cliamp.mobile.data.Repository
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationArtSource
import stream.cliamp.mobile.data.provider.ProviderAccount
import stream.cliamp.mobile.data.provider.ProviderStore
import stream.cliamp.mobile.ui.components.BackIconChip
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
import stream.cliamp.mobile.ui.theme.CliampShape
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalHapticsEnabled
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

private enum class Scope(val label: String) {
    All("all"), Media("local"), Radio("radio"), Pods("podcasts"),
    Tags("tags"), Providers("providers"),
}

/**
 * The app-wide fuzzy finder: one query spans local songs, favourites, the
 * radio directory, podcasts and providers.
 */
@Composable
fun SearchScreen(
    repository: Repository,
    podcasts: PodcastRepository,
    prefs: Prefs,
    localLibrary: LocalLibrary,
    providers: ProviderStore,
    current: Station? = null,
    playing: Boolean = false,
    onPlay: (Station, List<Station>) -> Unit,
    onOpenProvider: (ProviderAccount) -> Unit,
    onOpenShow: (PodcastShow) -> Unit,
    onOpenTag: (String) -> Unit,
    onBack: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val p = LocalPalette.current
    var filter by remember { mutableStateOf(Scope.All) }
    // Podcast search hits, kept local to this screen. The Podcasts tab shares
    // the same PodcastRepository, so routing search through podcasts.load()
    // left the tab stuck on the last search query instead of its own
    // top/category directory. The search still resolves shows by name, but
    // through a query-scoped fetch here rather than a rewrite of the shared state.
    var podcastHits by remember { mutableStateOf<List<PodcastShow>>(emptyList()) }
    // Cached episodes of subscribed shows, for the episode tail of results.
    // Resolved in the same debounced fetch as the show directory above.
    var episodeIndex by remember { mutableStateOf<List<Pair<PodcastShow, PodcastEpisode>>>(emptyList()) }

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

    val term = query.trim()

    // Debounce the directory: it is somebody else's server, not ours. The local
    // and radio fuzzy pass runs instantly on what we already hold.
    LaunchedEffect(term) {
        if (term.length < 2) {
            episodeIndex = emptyList()
            return@LaunchedEffect
        }
        delay(320)
        repository.loadDirectory(DirectoryQuery.Search(term), reset = true)
        // The podcast directory takes the same query, so a show can be found
        // by name. Unlike the Stations tab, the Podcasts tab keeps the search
        // rather than resetting it: Apple's search returns whole shows in one
        // request with nothing to page, so the searched list IS the directory
        // for as long as the query stands, and its header names the query.
        //
        // This used to go through podcasts.load(), which rewrites the shared
        // directory state the Podcasts tab reads, leaving that tab filtered by
        // whatever was last searched instead of its own top/category browse.
        // The search now keeps its own copy so it still resolves shows without
        // disturbing the tab.
        podcastHits = runCatching { PodcastDirectory.search(term) }.getOrDefault(emptyList())
        episodeIndex = runCatching { podcasts.subscribedEpisodes() }.getOrDefault(emptyList())
    }

    val subscriptions by podcasts.subscriptions.collectAsState(initial = emptyList())

    // Subscriptions are resident, the search half is whatever the debounced
    // query above just fetched, so a show can be found whether or not it is
    // already followed. podcastHits is this screen's own copy: the Podcasts
    // tab reads the same repository but must keep its own top/category browse.
    val shows = remember(subscriptions, podcastHits) {
        subscriptions + podcastHits
    }
    val subscribedFeeds = remember(subscriptions) { subscriptions.mapTo(HashSet()) { it.feedUrl } }

    val results = remember(
        term, filter, songs, favorites, recent, radio, tags, providerAccounts, shows, subscribedFeeds, episodeIndex,
    ) {
        GlobalSearch.run(
            term, songs, favorites, recent, radio, tags, providerAccounts, shows, subscribedFeeds, episodeIndex,
        )
    }.hits

    val shown = when (filter) {
        Scope.All -> results
        Scope.Media -> results.filter { it is SearchHit.Song || it is SearchHit.Favorite }
        Scope.Radio -> results.filter { it is SearchHit.StationHit }
        Scope.Pods -> results.filter { it is SearchHit.Show || it is SearchHit.Episode }
        Scope.Tags -> results.filter { it is SearchHit.Tag }
        Scope.Providers -> results.filter { it is SearchHit.Provider }
    }

    fun run(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        repository.loadDirectory(DirectoryQuery.Search(text), reset = true)
    }

    fun open(hit: SearchHit, queue: List<SearchHit>) {
        when (hit) {
            is SearchHit.Provider -> onOpenProvider(hit.account)
            is SearchHit.Show -> onOpenShow(hit.show)
            is SearchHit.Tag -> onOpenTag(hit.name)
            else -> hit.playable?.let { s ->
                onPlay(s, queue.mapNotNull { it.playable })
            }
        }
    }

    Column(Modifier.fillMaxSize().background(p.ground).navigationBarsPadding()) {
        ScreenHeader(divider = false) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gutter)
                    .padding(top = 10.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackIconChip(onClick = onBack)
                Spacer(Modifier.width(6.dp))
                CliampTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    placeholder = "Search",
                    imeAction = ImeAction.Go,
                    onAction = { run(query) },
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
                        current = current,
                        playing = playing,
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
        is SearchHit.Show, is SearchHit.Episode -> "podcasts"
        is SearchHit.Tag -> "tags"
        is SearchHit.Provider -> "providers"
    }
    return if (hit is SearchHit.Tag) "tags — global" else "$head — global"
}

@Composable
private fun HitArt(
    hit: SearchHit,
    accent: androidx.compose.ui.graphics.Color,
    active: Boolean = false,
    playing: Boolean = false,
) {
    val p = LocalPalette.current
    // Tags and provider rows carry no art; the type glyph is right.
    val station = when (hit) {
        is SearchHit.Song -> hit.station
        is SearchHit.Favorite -> hit.station
        is SearchHit.StationHit -> hit.station
        is SearchHit.Episode -> hit.station
        else -> null
    }
    // A known cover URL wins outright, wherever it lives: a show hands its
    // own artwork (episodes without one fall back to it upstream), an
    // episode or track carries its own. Local files never carry one, so
    // their embedded-art path is intact.
    val directUrl = when (hit) {
        is SearchHit.Show -> hit.show.artwork.takeIf { it.startsWith("http") }
        else -> station?.cover?.takeIf { it.startsWith("http") }
    }
    if (station == null && directUrl == null) {
        Icon(
            iconOf(hit), null, Modifier.size(15.dp),
            tint = p.inkTertiary,
        )
        return
    }

    var art by remember(station?.id ?: directUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(station?.id ?: directUrl) {
        delay(90)
        art = if (directUrl != null) StationArtSource.bitmapForUrlSmall(directUrl)?.asImageBitmap()
        else station?.let { StationArtSource.bitmapForSmall(it)?.asImageBitmap() }
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(CliampShape.small))
            .then(
                if (art != null) Modifier.background(p.panel)
                else Modifier.border(
                    1.dp,
                    if (active) accent else p.chipBorder,
                    RoundedCornerShape(CliampShape.small),
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art!!, station?.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(iconOf(hit), null, Modifier.size(15.dp), tint = p.inkTertiary)
        }
        // The playing station gets the same play/pause badge the station
        // rows wear, so a hit for what is on now reads as playing here too.
        if (active) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(22.dp)
                    .clip(RoundedCornerShape(CliampShape.tiny))
                    .background(accent.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) CliampIcons.Pause else CliampIcons.PlayRow,
                    null,
                    Modifier.size(11.dp),
                    tint = p.onAccent,
                )
            }
        }
    }
}

@Composable
private fun HitRow(
    hit: SearchHit,
    current: Station?,
    playing: Boolean = false,
    term: String,
    onClick: () -> Unit,
    accent: androidx.compose.ui.graphics.Color,
) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val enabled = LocalHapticsEnabled.current
    val currentUrl = when (hit) {
        is SearchHit.Song -> hit.station.url
        is SearchHit.Favorite -> hit.station.url
        is SearchHit.StationHit -> hit.station.url
        else -> null
    }
    val active = currentUrl != null && current?.url == currentUrl

    ListRow(
        rail = active,
        onClick = {
            if (enabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        verticalPadding = 9.dp,
        leading = { HitArt(hit, accent, active, playing && active) },
        trailing = { Mono(hit.origin, CliampType.meta, p.inkFaint) },
    ) {
        val title = when (hit) {
            is SearchHit.Song -> hit.station.name
            is SearchHit.Favorite -> hit.station.name
            is SearchHit.StationHit -> hit.station.name
            is SearchHit.Episode -> hit.station.name
            is SearchHit.Show -> hit.show.title
            is SearchHit.Tag -> "#${hit.name}"
            is SearchHit.Provider -> hit.account.label
        }
        val sub = when (hit) {
            is SearchHit.Song -> hit.station.artist
            is SearchHit.Favorite -> hit.station.meta.ifBlank { hit.station.name }
            is SearchHit.StationHit -> hit.station.meta
            is SearchHit.Episode -> hit.showTitle
            is SearchHit.Show -> hit.show.meta
            is SearchHit.Tag -> "${hit.count} stations"
            is SearchHit.Provider -> hit.specLabel
        }
        androidx.compose.material3.Text(
            text = highlight(title, term, accent),
            style = CliampType.rowPrimary.copy(color = if (active) accent else p.ink),
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
    is SearchHit.Show, is SearchHit.Episode -> CliampIcons.PodRow
    is SearchHit.Tag -> CliampIcons.ListShort
    is SearchHit.Provider -> CliampIcons.Search
}