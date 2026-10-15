package stream.kleeamp.mobile.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.LocalPalette

/** The standard ⋮ button trigger opening a bottom sheet on list rows. */
@Composable
fun OverflowButton(
    onOpen: () -> Unit,
    icon: ImageVector = KleeampIcons.More,
    size: Int = 15,
    tint: Color? = null,
) {
    val p = LocalPalette.current
    Box(
        Modifier.size(22.dp).microPress(onClick = onOpen).clip(RoundedCornerShape(KleeampShape.tiny)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, "menu", Modifier.size(size.dp), tint = tint ?: p.inkTertiary)
    }
}

