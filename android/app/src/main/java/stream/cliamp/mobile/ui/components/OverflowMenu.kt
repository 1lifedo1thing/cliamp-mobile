package stream.cliamp.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import stream.cliamp.mobile.ui.theme.CliampShape
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

/** A single entry in an [OverflowMenu]. */
data class OverflowItem(
    val label: String,
    val action: () -> Unit,
    val color: Color? = null,
)

/**
 * A compact ⋮ overflow menu (a `Popup` whose trigger is the trigger composable
 * passed in). Used on rows/tracks to surface things like play next, add to
 * queue and replace queue without cluttering the row itself.
 */
@Composable
fun OverflowMenu(
    trigger: @Composable (() -> Unit) -> Unit,
    items: List<OverflowItem>,
    menuWidth: Int = 170,
) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        trigger { open = true }
        if (open) {
            Popup(
                onDismissRequest = { open = false },
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, 8),
            ) {
                Column(
                    Modifier.width(menuWidth.dp).clip(RoundedCornerShape(CliampShape.small))
                        .background(p.ground).border(1.dp, p.hairlineRegion, RoundedCornerShape(CliampShape.small)),
                ) {
                    items.forEachIndexed { i, item ->
                        if (i > 0) HairlineDivider(region = true)
                        Row(
                            Modifier.fillMaxWidth().microPress {
                                open = false
                                item.action()
                            }.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Mono(item.label, CliampType.chip, item.color ?: p.ink)
                        }
                    }
                }
            }
        }
    }
}

/** The standard ⋮ button trigger used by [OverflowMenu] on list rows. */
@Composable
fun OverflowButton(
    onOpen: () -> Unit,
    icon: ImageVector = CliampIcons.More,
    size: Int = 15,
    tint: Color? = null,
) {
    val p = LocalPalette.current
    Box(
        Modifier.size(22.dp).microPress(onClick = onOpen).clip(RoundedCornerShape(CliampShape.tiny)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, "menu", Modifier.size(size.dp), tint = tint ?: p.inkTertiary)
    }
}

