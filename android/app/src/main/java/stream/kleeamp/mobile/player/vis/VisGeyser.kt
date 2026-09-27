package stream.kleeamp.mobile.player.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import stream.kleeamp.mobile.theme.LocalPalette

@Composable
internal fun VisGeyser(frame: GeyserFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        for (drop in frame.core.drops()) {
            val life = (drop.life / 90f).coerceIn(0f, 1f)
            if (life <= 0f) continue
            drawCircle(
                visTier(p, drop.tier.coerceIn(0, 2)).copy(alpha = (0.25f + 0.75f * life)),
                (size.minDimension * 0.009f + 0.8f).coerceIn(0.8f, 4f),
                Offset(
                    drop.x / 100f * size.width,
                    drop.y / 160f * size.height,
                ),
            )
        }
    }
}
