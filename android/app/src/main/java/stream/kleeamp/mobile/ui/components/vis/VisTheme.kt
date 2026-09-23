package stream.kleeamp.mobile.ui.components.vis

import androidx.compose.ui.graphics.Color
import stream.kleeamp.mobile.theme.KleeampPalette

internal fun visTier(p: KleeampPalette, tier: Int): Color = when (tier) {
    2 -> p.accentBright
    1 -> p.accent
    else -> p.accentBevel
}
