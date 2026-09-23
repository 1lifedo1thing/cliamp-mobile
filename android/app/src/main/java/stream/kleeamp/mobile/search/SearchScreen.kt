package stream.kleeamp.mobile.search

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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.art.PlaceholderArt
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.servers.ProviderAccount
import stream.kleeamp.mobile.servers.displayName
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.KleeampTextField
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.HairlineDivider
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.ScreenHeader
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalHapticsEnabled
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * The app-wide fuzzy finder: one query spans local songs, favourites, the
 * radio directory, podcasts and providers.
 */
@Composable
// Screen signature: state in, callbacks out; bundling would hide the data flow.
@Suppress("LongParameterList")
fun SearchScreen(
    vm: SearchViewModel,
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
    val uiState by vm.state.collectAsState()
    // The query text itself stays hoisted in the root; the VM mirrors it to
    // drive the debounced directory fetch and the result computation.
    LaunchedEffect(query) { vm.onEvent(SearchViewModel.Event.QueryChanged(query)) }
    val filter = uiState.filter
    val term = uiState.term
    val results = uiState.results
    val shown = uiState.shown

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
                BackChevron(onBack)
                Spacer(Modifier.width(8.dp))
                KleeampTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    placeholder = "Search",
                    imeAction = ImeAction.Go,
                    onAction = { vm.onEvent(SearchViewModel.Event.Submitted(query)) },
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(start = Gutter, end = Gutter, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                SearchScope.entries.forEach { s ->
                    Chip(s.label, filter == s, onClick = { vm.onEvent(SearchViewModel.Event.FilterChanged(s)) })
                }
            }
            HairlineDivider(region = true)
        }

        if (results.isEmpty() && term.isNotBlank()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 20.dp)) {
                Mono("no hits anywhere for \"$term\"", KleeampType.rowSecondary, p.inkFaint)
            }
        }

        // Mixed scopes group under their own stable label: one "label:X"
        // key per section, unique row keys inside. A single label taken
        // from the first hit misdescribed the rest and changed keys on
        // every keystroke. Sections keep chip order (not first-seen), so
        // late async arrivals for the same term grow sections in place
        // instead of reordering them and yanking the viewport.
        val groups = remember(shown) {
            shown.groupBy(::sectionOf).entries
                .sortedBy { SECTION_ORDER.indexOf(it.key).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
                .associate { it.key to it.value }
        }
        val listState = rememberLazyListState()
        // A new term or filter is a new result set: pin to the top. Without
        // this the list keeps its old index while the content morphs under
        // it - narrowing 200 hits to 5 from index 50 clamps to the end, and
        // async insertions above shove the viewport mid-list.
        LaunchedEffect(term, filter) { listState.scrollToItem(0) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            // No placeholder while empty: a keyed trailing spacer would
            // anchor the scroll, so the moment the first results land the
            // fresh list jumps to the bottom following its key. With no
            // prior keys there is nothing to restore, and it opens at top.
            if (groups.isNotEmpty()) {
                groups.forEach { (label, hits) ->
                    item(key = "label:$label") { SectionLabel(label) }
                    items(hits, key = { it.key }) { hit ->
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
                item(key = "bottom-spacer") { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

/** Section order mirrors the scope chips; unknown sections sort last. */
private val SECTION_ORDER = listOf(
    "local — global",
    "radio — global",
    "podcasts — global",
    "tags — global",
    "providers — global",
)

private fun sectionOf(hit: SearchHit): String {
    val head = when (hit) {
        is SearchHit.Song -> "local"
        // Favourites and recents file by what the station IS, not by the
        // fact it was starred or heard: a recent radio stream is radio,
        // not local, and never wears the fav tag unless starred.
        is SearchHit.Favorite, is SearchHit.Recent -> when (hit.playable?.source) {
            StationSource.Local -> "local"
            StationSource.Podcast -> "podcasts"
            else -> "radio"
        }
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
        is SearchHit.Recent -> hit.station
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

    val context = LocalContext.current
    val artKey = station?.id ?: directUrl
    val cached = remember(artKey) {
        (directUrl?.let { StationArtSource.cachedSmallUrl(it) }
            ?: station?.let { StationArtSource.cachedSmall(it) })?.asImageBitmap()
    }
    // Bundled design first, keyed exactly like StationThumb so a coverless
    // station wears the same design here as in the stations list - except
    // local and provider songs, which wear the empty plate instead. Never
    // key by cover URL: signed provider URLs rotate, which would change the
    // design on every list build. The lookup below only ever upgrades to
    // real art.
    val placeholder = remember(artKey) {
        val key = when {
            station != null && station.bundledCover -> station.id.ifBlank { station.url }
            station != null -> null
            hit is SearchHit.Show -> hit.show.feedUrl
            else -> null
        }
        key?.let { PlaceholderArt.thumbnailFor(context, it)?.asImageBitmap() }
    }
    var art by remember(artKey) { mutableStateOf(cached ?: placeholder) }
    LaunchedEffect(artKey) {
        if (cached != null) return@LaunchedEffect
        delay(90)
        (if (directUrl != null) StationArtSource.bitmapForUrlSmall(directUrl)?.asImageBitmap()
        else station?.let { StationArtSource.bitmapForSmall(it)?.asImageBitmap() })?.let { art = it }
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .then(
                if (art != null) Modifier.background(p.panel)
                else Modifier.border(
                    1.dp,
                    if (active) accent else p.chipBorder,
                    RoundedCornerShape(KleeampShape.small),
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
                    .clip(RoundedCornerShape(KleeampShape.tiny))
                    .background(accent.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) KleeampIcons.Pause else KleeampIcons.PlayRow,
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
        is SearchHit.Recent -> hit.station.url
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
        trailing = { Mono(hit.origin, KleeampType.meta, p.inkFaint) },
    ) {
        val title = when (hit) {
            is SearchHit.Song -> hit.station.name
            is SearchHit.Favorite -> hit.station.name
            is SearchHit.Recent -> hit.station.name
            is SearchHit.StationHit -> hit.station.name
            is SearchHit.Episode -> hit.station.name
            is SearchHit.Show -> hit.show.title
            is SearchHit.Tag -> "#${hit.name}"
            is SearchHit.Provider -> hit.account.displayName()
        }
        val sub = when (hit) {
            is SearchHit.Song -> hit.station.artist
            is SearchHit.Favorite -> hit.station.meta.ifBlank { hit.station.name }
            is SearchHit.Recent -> hit.station.meta.ifBlank { hit.station.name }
            is SearchHit.StationHit -> hit.station.meta
            is SearchHit.Episode -> hit.showTitle
            is SearchHit.Show -> hit.show.meta
            is SearchHit.Tag -> "${hit.count} stations"
            is SearchHit.Provider -> hit.specLabel
        }
        androidx.compose.material3.Text(
            text = remember(title, term, accent) { highlight(title, term, accent) },
            style = KleeampType.rowPrimary.copy(color = if (active) accent else p.ink),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (sub.isNotBlank()) {
            Mono(sub, KleeampType.rowSecondary, p.inkTertiary, maxLines = 1)
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
    is SearchHit.Song, is SearchHit.Favorite, is SearchHit.Recent -> KleeampIcons.MusicNote
    is SearchHit.StationHit -> KleeampIcons.StationsTab
    is SearchHit.Show, is SearchHit.Episode -> KleeampIcons.PodRow
    is SearchHit.Tag -> KleeampIcons.ListShort
    is SearchHit.Provider -> KleeampIcons.Search
}
