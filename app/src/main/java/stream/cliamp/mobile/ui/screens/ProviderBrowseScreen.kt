package stream.cliamp.mobile.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.provider.ProviderAccount
import stream.cliamp.mobile.data.provider.ProviderAlbum
import stream.cliamp.mobile.data.provider.ProviderArtist
import stream.cliamp.mobile.data.provider.ProviderTrack
import stream.cliamp.mobile.data.provider.browseClient
import stream.cliamp.mobile.data.provider.toStation
import stream.cliamp.mobile.ui.components.Chip
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.OverflowButton
import stream.cliamp.mobile.ui.components.OverflowItem
import stream.cliamp.mobile.ui.components.OverflowMenu
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

private enum class Root(val label: String, val listType: String) {
    Newest("newest", "newest"),
    Frequent("most played", "frequent"),
    AZ("a-z", "az"),
    Artists("artists", ""),
    Starred("starred", ""),
}

/** Where in the provider's own hierarchy we are. */
private sealed interface Node {
    data object Home : Node
    data class Artist(val id: String, val name: String) : Node
    data class Album(val id: String, val name: String, val artist: String) : Node
}

/**
 * Browses one provider's library: albums, artists, starred tracks, drilling
 * into an album's track list. Playing a track queues the whole album.
 *
 * The screen only knows the generic [ProviderBrowseClient]; Subsonic and
 * Jellyfin/Emby behind it. Roots some providers do not offer are just hidden.
 */
@Composable
fun ProviderBrowseScreen(
    account: ProviderAccount,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onPlay: (Station, List<Station>) -> Unit,
    onOpenPlayer: () -> Unit,
    onAddToQueue: (Station) -> Unit = {},
    onPlayNext: (Station) -> Unit = {},
) {
    val p = LocalPalette.current
    val client = remember(account.id) { account.browseClient() }
    // Roots with no semantics on the server are dropped from the chip row.
    val roots = remember(account.providerKey) {
        if (account.providerKey == "jellyfin" || account.providerKey == "emby" ||
            account.providerKey == "plex" || account.providerKey == "abs"
        ) {
            listOf(Root.Newest, Root.AZ)
        } else {
            Root.entries
        }
    }

    var stack by remember(account.id) { mutableStateOf<List<Node>>(listOf(Node.Home)) }
    var root by remember(account.id) { mutableStateOf(roots.first()) }
    var albums by remember { mutableStateOf<List<ProviderAlbum>>(emptyList()) }
    var artists by remember { mutableStateOf<List<ProviderArtist>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<ProviderTrack>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    val here = stack.last()

    LaunchedEffect(account.id, here, root) {
        busy = true
        failure = null
        albums = emptyList(); artists = emptyList(); tracks = emptyList()
        when (val n = here) {
            Node.Home -> when (root) {
                Root.Artists -> client.artists()
                    .onSuccess { artists = it }
                    .onFailure { failure = it.message }
                Root.Starred -> client.starred()
                    .onSuccess { tracks = it }
                    .onFailure { failure = it.message }
                else -> client.albums(root.listType)
                    .onSuccess { albums = it }
                    .onFailure { failure = it.message }
            }
            is Node.Artist -> client.artistAlbums(n.id)
                .onSuccess { albums = it }
                .onFailure { failure = it.message }
            is Node.Album -> client.albumTracks(n.id)
                .onSuccess { tracks = it }
                .onFailure { failure = it.message }
        }
        busy = false
    }

    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) else onBack() }

    Column(Modifier.fillMaxSize().background(p.ground)) {
        stream.cliamp.mobile.ui.components.ScreenHeader {
            Row(
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.clickable { pop() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(CliampIcons.Prev, "back", Modifier.size(width = 15.dp, height = 12.dp), tint = p.inkSecondary)
                    Mono("back", CliampType.rowSecondary, p.inkSecondary)
                }
                // clear of QueueBar
                Mono(
                    "EDIT",
                    CliampType.sectionLabel,
                    p.inkTertiary,
                    Modifier.padding(end = 56.dp).clickable(onClick = onEdit),
                )
            }
            Box(Modifier.padding(horizontal = Gutter, vertical = 4.dp)) {
                Mono(
                    when (val n = here) {
                        Node.Home -> account.label.ifBlank { "provider" }
                        is Node.Artist -> n.name
                        is Node.Album -> n.name
                    },
                    CliampType.screenTitle, p.ink, maxLines = 1,
                )
            }
            if (here == Node.Home) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    roots.forEach { r -> Chip(r.label, root == r, onClick = { root = r }) }
                }
            } else {
                Spacer(Modifier.height(10.dp))
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            failure?.let { msg ->
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 20.dp)) {
                        Mono(msg, CliampType.rowSecondary, p.destructiveInk)
                    }
                }
            }
            if (busy && failure == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 20.dp)) {
                        Mono("loading…", CliampType.rowSecondary, p.inkFaint)
                    }
                }
            }

            if (artists.isNotEmpty()) {
                item { SectionLabel("artists — ${artists.size}") }
                items(artists.size, key = { "ar:${artists[it].id}" }) { i ->
                    val a = artists[i]
                    ListRow(
                        onClick = { stack = stack + Node.Artist(a.id, a.name) },
                        verticalPadding = 11.dp,
                        trailing = {
                            if (a.albumCount > 0) Mono("${a.albumCount}", CliampType.meta, p.inkFaint)
                        },
                    ) {
                        Mono(a.name, CliampType.rowPrimary, p.ink, maxLines = 1)
                    }
                }
            }

            if (albums.isNotEmpty()) {
                item { SectionLabel("albums — ${albums.size}") }
                items(albums.size, key = { "al:${albums[it].id}" }) { i ->
                    val a = albums[i]
                    ListRow(
                        onClick = { stack = stack + Node.Album(a.id, a.name, a.artist) },
                        verticalPadding = 11.dp,
                        trailing = {
                            if (a.songCount > 0) Mono("${a.songCount}", CliampType.meta, p.inkFaint)
                        },
                    ) {
                        Mono(a.name, CliampType.rowPrimary, p.ink, maxLines = 1)
                        Mono(
                            listOfNotNull(
                                a.artist.takeIf { it.isNotBlank() },
                                a.year.takeIf { it > 0 }?.toString(),
                            ).joinToString(" · "),
                            CliampType.rowSecondary, p.inkTertiary, maxLines = 1,
                        )
                    }
                }
            }

            if (tracks.isNotEmpty()) {
                item { SectionLabel("tracks — ${tracks.size}") }
                items(tracks.size, key = { "tr:${tracks[it].id}" }) { i ->
                    val t = tracks[i]
                    ListRow(
                        onClick = {
                            val queue = tracks.map { it.toStation(account, client.trackCover(it.id)) }
                            onPlay(queue[i], queue)
                            onOpenPlayer()
                        },
                        verticalPadding = 11.dp,
                        leading = {
                            Box(
                                Modifier.size(28.dp).clip(RoundedCornerShape(4.dp))
                                    .border(1.dp, p.chipBorder, RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(CliampIcons.PlayRow, null, Modifier.size(11.dp), tint = p.inkTertiary)
                            }
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Mono(clockOf(t.duration), CliampType.meta, p.inkFaint)
                                OverflowMenu(
                                    trigger = { open -> OverflowButton(open) },
                                    items = listOf(
                                        OverflowItem(label = "play next", action = { onPlayNext(t.toStation(account, client.trackCover(t.id))) }),
                                        OverflowItem(label = "add to queue", action = { onAddToQueue(t.toStation(account, client.trackCover(t.id))) }),
                                    ),
                                )
                            }
                        },
                    ) {
                        Mono(t.title, CliampType.rowPrimary, p.ink, maxLines = 1)
                        Mono(
                            listOfNotNull(
                                t.artist.takeIf { it.isNotBlank() },
                                t.album.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            CliampType.rowSecondary, p.inkTertiary, maxLines = 1,
                        )
                    }
                }
            }

            if (!busy && failure == null && artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 20.dp)) {
                        Mono("nothing here", CliampType.rowSecondary, p.inkFaint)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun clockOf(seconds: Int): String =
    if (seconds <= 0) "" else "%d:%02d".format(seconds / 60, seconds % 60)