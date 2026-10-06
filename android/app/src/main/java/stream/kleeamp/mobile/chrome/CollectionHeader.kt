package stream.kleeamp.mobile.chrome

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * One collection page's cover block: the art tile beside the title, the
 * shape every channel and playlist page opens with. The art is caller
 * content (a cover, a seed plate, a glyph) filling the 140dp tile; the
 * title and meta lines are plain strings, and [actions] is the row of
 * chips and keys under them (play-all, shuffle, subscribe) — the podcast
 * show header's subscribe spot. Mirrors the cliamp radio channel header
 * exactly.
 */
@Composable
fun CollectionHeader(
    title: String,
    meta: String = "",
    description: String = "",
    actions: @Composable ColumnScope.() -> Unit = {},
    art: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(KleeampShape.small))
                    .border(1.dp, p.frameBorder, RoundedCornerShape(KleeampShape.small)),
            ) {
                art()
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Mono(title, KleeampType.rowPrimaryMedium, p.ink, maxLines = 2)
                if (meta.isNotBlank()) {
                    Mono(meta, KleeampType.rowSecondary, p.inkSecondary, maxLines = 1)
                }
                actions()
            }
        }
        if (description.isNotBlank()) {
            Box(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, bottom = 14.dp)) {
                Mono(description, KleeampType.rowSecondary, p.inkTertiary, maxLines = 4)
            }
        }
    }
}

/**
 * The hero controls under a collection title: a play-all chip next to the
 * shuffle key. The shuffle key shuffle-plays the page — a random start with
 * shuffle left on — while the player toolbar keeps the only switch that
 * turns shuffle back off. Play-all starts shuffled exactly when the toolbar
 * shows shuffle on.
 */
@Composable
fun CollectionActions(
    shuffled: Boolean,
    onPlayAll: () -> Unit,
    onShufflePlay: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Chip("play all", selected = false, onClick = onPlayAll)
        ShuffleAction(shuffled = shuffled, onToggleShuffle = onShufflePlay)
    }
}

/** The global shuffle key for hero rows: 15dp icon in a 40dp touch target. */
@Composable
fun ShuffleAction(
    shuffled: Boolean,
    onToggleShuffle: () -> Unit,
) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .microPress(onClick = onToggleShuffle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            KleeampIcons.Shuffle,
            if (shuffled) "stop shuffling" else "shuffle",
            Modifier.size(15.dp),
            tint = if (shuffled) p.accent else p.inkSecondary,
        )
    }
}
