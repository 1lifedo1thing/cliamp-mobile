package stream.kleeamp.mobile.chrome

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.art.SeedPlate
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * What a menu subject is. The sheet never branches on screens or sources -
 * it branches on this, so a radio stream, a local song and a provider
 * track share the STATION menu while episodes and shows get their own.
 */
enum class MenuKind { STATION, EPISODE, SHOW }

/** One destructive tail action: drop from a playlist, remove a station or a download, delete a file. */
data class DestructiveAction(
    val label: String,
    val subtitle: String?,
    val onClick: () -> Unit,
)

/** One rendered row: label, optional subtitle, icon, handler. No Share exists yet. */
data class MenuAction(
    val id: String,
    val label: String,
    val subtitle: String?,
    val icon: ImageVector,
    val filledIcon: ImageVector? = null,
    val active: Boolean = false,
    val tint: Color? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Everything the menu needs to know about an item: its kind, its current
 * state, and a handler per capability. A null handler means the capability
 * is absent and its action is not shown - there is deliberately no Share
 * slot until sharing exists.
 */
data class MenuSubject(
    val kind: MenuKind,
    val favorite: Boolean = false,
    val subscribed: Boolean = false,
    val downloaded: Boolean = false,
    val downloading: Boolean = false,
    val downloadFailed: Boolean = false,
    val playedDone: Boolean = false,
    val infoAvailable: Boolean = false,
    val destructive: DestructiveAction? = null,
    val onPlayNext: (() -> Unit)? = null,
    val onQueue: (() -> Unit)? = null,
    val onToggleFavorite: (() -> Unit)? = null,
    val onAddToPlaylist: (() -> Unit)? = null,
    val onDownload: (() -> Unit)? = null,
    val onCancelDownload: (() -> Unit)? = null,
    val onRemoveDownload: (() -> Unit)? = null,
    val onMarkPlayed: (() -> Unit)? = null,
    val onForgetPlayed: (() -> Unit)? = null,
    val onInfo: (() -> Unit)? = null,
    val onToggleSubscribe: (() -> Unit)? = null,
)

/**
 * The single place menu decisions live: item state in, rows out. Screens
 * build the subject from the state they already hold and render whatever
 * comes back - no screen hardcodes its own action list.
 */
@Composable
fun menuActions(s: MenuSubject): List<MenuAction> {
    val p = LocalPalette.current
    return buildList {
        if (s.kind == MenuKind.SHOW) {
            s.onToggleSubscribe?.let { toggle ->
                add(
                    MenuAction(
                        id = "subscribe",
                        label = if (s.subscribed) "unsubscribe" else "subscribe",
                        subtitle = if (s.subscribed) "stop following this show" else "follow this show",
                        icon = if (s.subscribed) KleeampIcons.Xmark else KleeampIcons.Plus,
                        tint = if (s.subscribed) null else p.accent,
                        onClick = toggle,
                    ),
                )
            }
            return@buildList
        }
        s.onPlayNext?.let { playNext ->
            add(
                MenuAction(
                    id = "play-next",
                    label = "Play Next",
                    subtitle = "play right after this one",
                    icon = KleeampIcons.Next,
                    tint = p.accent,
                    onClick = playNext,
                ),
            )
        }
        s.onQueue?.let { queue ->
            add(
                MenuAction(
                    id = "queue",
                    label = "Add to Up Next",
                    subtitle = "queue at the end of Up Next",
                    icon = KleeampIcons.UpNextTabLines,
                    onClick = queue,
                ),
            )
        }
        s.onToggleFavorite?.let { toggle ->
            add(
                MenuAction(
                    id = "favorite",
                    label = if (s.favorite) "Remove from Favorites" else "Add to Favorites",
                    subtitle = if (s.favorite) "take it out of your favorites" else "mark it as your favorite",
                    icon = KleeampIcons.Heart,
                    filledIcon = KleeampIcons.HeartFilled,
                    active = s.favorite,
                    tint = if (s.favorite) p.accent else null,
                    onClick = toggle,
                ),
            )
        }
        s.onAddToPlaylist?.let { pick ->
            add(
                MenuAction(
                    id = "playlist",
                    label = "Add to Playlist",
                    subtitle = "save to an existing or new playlist",
                    icon = KleeampIcons.PlaylistAdd,
                    onClick = pick,
                ),
            )
        }
        // Episodes gain exactly one download row for their current state:
        // remove a fetched file, cancel a running fetch, retry a failed
        // one, or fetch it offline.
        if (s.kind == MenuKind.EPISODE) {
            when {
                s.downloaded && s.onRemoveDownload != null -> add(
                    MenuAction(
                        id = "remove-download",
                        label = "Remove Download",
                        subtitle = "free up the space",
                        icon = KleeampIcons.Trash,
                        onClick = s.onRemoveDownload,
                    ),
                )
                s.downloading && s.onCancelDownload != null -> add(
                    MenuAction(
                        id = "cancel-download",
                        label = "Cancel Download",
                        subtitle = "stop this download",
                        icon = KleeampIcons.Xmark,
                        onClick = s.onCancelDownload,
                    ),
                )
                s.downloadFailed && s.onDownload != null -> add(
                    MenuAction(
                        id = "download",
                        label = "Retry Download",
                        subtitle = "try fetching it again",
                        icon = KleeampIcons.Download,
                        onClick = s.onDownload,
                    ),
                )
                s.onDownload != null -> add(
                    MenuAction(
                        id = "download",
                        label = "Download",
                        subtitle = "save this episode offline",
                        icon = KleeampIcons.Download,
                        onClick = s.onDownload,
                    ),
                )
            }
            if (s.playedDone && s.onForgetPlayed != null) {
                add(
                    MenuAction(
                        id = "unplayed",
                        label = "Mark Unplayed",
                        subtitle = "clear the finished mark",
                        icon = KleeampIcons.Xmark,
                        onClick = s.onForgetPlayed,
                    ),
                )
            } else if (!s.playedDone && s.onMarkPlayed != null) {
                add(
                    MenuAction(
                        id = "played",
                        label = "Mark Played",
                        subtitle = "remember it as finished",
                        icon = KleeampIcons.Check,
                        onClick = s.onMarkPlayed,
                    ),
                )
            }
        }
        if (s.infoAvailable && s.onInfo != null) {
            add(
                MenuAction(
                    id = "info",
                    label = if (s.kind == MenuKind.EPISODE) "Episode Info" else "Song Info",
                    subtitle = "view details and stats",
                    icon = KleeampIcons.Info,
                    onClick = s.onInfo,
                ),
            )
        }
        s.destructive?.let { danger ->
            add(
                MenuAction(
                    id = "destructive",
                    label = danger.label,
                    subtitle = danger.subtitle,
                    icon = KleeampIcons.Trash,
                    destructive = true,
                    onClick = danger.onClick,
                ),
            )
        }
    }
}

/** Header art for a station row: real cover or its seeded plate. */
@Composable
fun StationMenuArt(station: Station, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val art = rememberArt(station = station)
    Box(
        modifier
            .clip(RoundedCornerShape(KleeampShape.small))
            .background(p.panel),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Crop)
        } else {
            SeedPlate(
                key = station.id.ifBlank { station.url },
                name = station.name,
                modifier = Modifier.fillMaxWidth(),
                radius = KleeampShape.small,
            )
        }
    }
}

/** Header art for a podcast show: catalogue art or its generated plate. */
@Composable
fun ShowMenuArt(artwork: String, key: String, name: String?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val art = rememberArt(url = artwork)
    Box(
        modifier
            .clip(RoundedCornerShape(KleeampShape.small))
            .background(p.panel),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Crop)
        } else {
            SeedPlate(
                key = key,
                name = name,
                modifier = Modifier.fillMaxWidth(),
                radius = KleeampShape.small,
            )
        }
    }
}

/**
 * One reusable contextual bottom sheet: drag handle, item header, action
 * rows, destructive tail. Dismisses on scrim tap, swipe-down and back -
 * all native ModalBottomSheet behavior. The header stays fixed while a
 * long action list scrolls; a short list shrinks to fit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextMenuSheet(
    title: String,
    subtitle: String,
    art: @Composable () -> Unit,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
) {
    MenuSheetShell(onDismiss = onDismiss, header = {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(52.dp)) { art() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Mono(title.ifBlank { "unknown" }, KleeampType.trackTitleCompact, LocalPalette.current.ink, maxLines = 2)
                if (subtitle.isNotBlank()) {
                    Mono(subtitle, KleeampType.rowSecondary, LocalPalette.current.inkTertiary, maxLines = 2)
                }
            }
        }
    }) {
        val (safe, danger) = actions.partition { !it.destructive }
        safe.forEach { MenuActionRow(it, onDismiss) }
        if (danger.isNotEmpty()) {
            HairlineDivider(region = true)
            danger.forEach { MenuActionRow(it, onDismiss) }
        }
    }
}

/**
 * The sheet chrome both menus and pickers share: rounded top, dimmed
 * scrim, drag handle, fixed header, scrolling body. One component, so a
 * timer or speed sheet can never drift from the menu look.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheetShell(
    onDismiss: () -> Unit,
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = p.panelRaised,
        contentColor = p.ink,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 2.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(KleeampShape.tiny))
                    .background(p.chipBorder),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
            header()
            HairlineDivider(region = true)
            Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
                content()
            }
            Spacer(Modifier.navigationBarsPadding().height(14.dp))
        }
    }
}

/**
 * A picker row for timer/speed sheets: label left, check right when
 * selected. The checkmark is the app's selection language, same as the
 * playlist picker's ticked rows.
 */
@Composable
fun SheetOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .microPress(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Mono(label, KleeampType.rowPrimary, if (selected) p.accent else p.ink, maxLines = 1)
            subtitle?.let { Mono(it, KleeampType.rowSecondary, p.inkTertiary, maxLines = 2) }
        }
        if (selected) {
            Icon(KleeampIcons.Check, null, Modifier.size(18.dp), tint = p.accent)
        }
    }
}

@Composable
private fun MenuActionRow(action: MenuAction, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val color = when {
        action.destructive -> p.destructiveInk
        else -> p.ink
    }
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .microPress {
                onDismiss()
                action.onClick()
            }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            if (action.active && action.filledIcon != null) action.filledIcon else action.icon,
            null,
            Modifier.size(20.dp),
            tint = when {
                action.destructive -> p.destructiveInk
                action.tint != null -> action.tint
                else -> p.inkSecondary
            },
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Mono(action.label, KleeampType.rowPrimary, color, maxLines = 1)
            action.subtitle?.let { Mono(it, KleeampType.rowSecondary, p.inkTertiary, maxLines = 2) }
        }
    }
}
