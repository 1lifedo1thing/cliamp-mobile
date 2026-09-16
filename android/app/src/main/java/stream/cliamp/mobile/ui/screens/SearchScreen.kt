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
import stream.cliamp.mobile.data.PodcastShow
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.playback.PlaybackContext
import stream.cliamp.mobile.data.StationArtSource
import stream.cliamp.mobile.data.provider.ProviderAccount
import stream.cliamp.mobile.ui.components.BackChevron
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.CliampTextField
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.ScreenHeader
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.search.Fuzzy
import stream.cliamp.mobile.ui.search.SearchHit
import stream.cliamp.mobile.ui.theme.CliampShape
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalHapticsEnabled
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

/**
 * The app-wide fuzzy finder: one query spans local songs, favourites, the
 * radio directory, podcasts and providers.
 */
@Composable
fun SearchScreen(
    vm: SearchViewModel,
    current: Station? = null,
    playing: Boolean = false,
    onPlay: (Station, List<Station>, PlaybackContext) -> Unit,
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
                onPlay(s, queue.mapNotNull { it.playable }, PlaybackContext.Search(term, filter.name))
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
                CliampTextField(
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