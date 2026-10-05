package stream.kleeamp.mobile.chrome

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
 * title and meta lines are plain strings. Mirrors the cliamp radio
 * channel header exactly.
 */
@Composable
fun CollectionHeader(
    title: String,
    meta: String = "",
    description: String = "",
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
            }
        }
        if (description.isNotBlank()) {
            Box(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, bottom = 14.dp)) {
                Mono(description, KleeampType.rowSecondary, p.inkTertiary, maxLines = 4)
            }
        }
    }
}
