package stream.kleeamp.mobile.chrome

import androidx.compose.runtime.Composable
import stream.kleeamp.mobile.theme.LocalPalette

/**
 * The ⋮ row menu for anything playable: favourites toggle, add-to-playlist
 * picker, add to Up Next, and info when it can be shown (null [onInfo]
 * hides the entry). Row-specific actions (remove, drop, downloads…)
 * append after the shared four via [extra].
 */
@Composable
fun StationMenu(
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAddToQueue: () -> Unit,
    onInfo: (() -> Unit)?,
    extra: List<OverflowItem> = emptyList(),
    buttonSize: Int = 16,
) {
    val p = LocalPalette.current
    OverflowMenu(
        trigger = { open -> OverflowButton(open, size = buttonSize) },
        items = buildList {
            add(
                OverflowItem(
                    if (favorite) "remove from favorites" else "add to favorites",
                    color = p.ink,
                    action = onToggleFavorite,
                ),
            )
            add(OverflowItem("add to playlist", color = p.ink, action = onAddToPlaylist))
            add(OverflowItem("add to Up Next", color = p.ink, action = onAddToQueue))
            onInfo?.let { show -> add(OverflowItem("info", color = p.ink, action = show)) }
            addAll(extra)
        },
    )
}
