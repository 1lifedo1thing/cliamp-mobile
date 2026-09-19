package stream.kleeamp.mobile.ui.components.vis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import stream.kleeamp.mobile.ui.theme.LocalPalette
import kotlin.math.floor

@Composable
internal fun VisClassicLed(frame: ClassicLedFrame, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame.frame
        val bars = frame.columns
        if (bars == 0) return@Canvas
        val ledRows = 8
        val ledH = size.height / ledRows
        val slot = size.width / bars
        val barW = slot * 0.7f
        for (b in 0 until bars) {
            val body = frame.body[b].coerceIn(0f, 1f)
            val peak = frame.peaks[b].coerceIn(0f, 1f)
            val lit = floor(body * ledRows + 1e-6).toInt()
            val peakRow = floor(peak * ledRows + 1e-6).toInt().coerceIn(0, ledRows - 1)
            val showPeak = peak > body + 0.5f / ledRows && peakRow >= lit
            val x = b * slot + (slot - barW) / 2f
            if (lit > 0) {
                drawRect(
                    color = p.accent,
                    topLeft = Offset(x, size.height - lit * ledH),
                    size = Size(barW, lit * ledH),
                )
            }
            if (showPeak) {
                drawRect(
                    color = p.peak,
                    topLeft = Offset(x, size.height - (peakRow + 1) * ledH),
                    size = Size(barW, ledH),
                )
            }
        }
    }
}
