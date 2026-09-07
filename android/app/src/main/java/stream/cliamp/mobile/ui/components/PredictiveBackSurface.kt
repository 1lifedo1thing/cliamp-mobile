package stream.cliamp.mobile.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * The surface currently in front of the page behind it. Loads the predictive
 * back gesture the way the system does: a swipe from the edge rides this
 * surface along with the finger (edge-aware, so a right-edge swipe slides it
 * the other way). If the finger releases past the commit threshold it glides
 * out on a slow, damped spring and [onBack] pops the page; if the gesture is
 * revoked it eases back to rest on the same spring, leaving the page in place,
 * and the whole exchange is cancelable the whole time the finger is down.
 * The three-button back carries no gesture, so it pops instantly, as Android
 * does. [onProgress] hands the signed progress (0..1 left-edge, 0..-1 right)
 * to the caller so the page behind can preview itself (scale down) while the
 * front surface slides. Every screen that has a page deeper than itself wraps
 * that page in one of these, which is what makes every back - tab, overlay,
 * pane, folder - an animated return to the previous page.
 */
@Composable
fun PredictiveBackSurface(
    enabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onProgress: (Float) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // Damped and quick enough to feel snappy but never springy; used for both
    // the commit glide and the revoke ease, so a cancelled gesture is just the
    // same motion in reverse.
    val ease = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    PredictiveBackHandler(enabled = enabled) { progress ->
        try {
            progress.collect { event ->
                val dir = if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                offset.snapTo(dir * event.progress)
                onProgress(offset.value)
            }
            // The gesture committed. The sheet glides out on the slow spring
            // while the preview rides OUT with it on the same motion (start ->
            // 0 as the cover clears), so the page behind grows back the whole
            // way and never waits for the cover to leave before snapping.
            if (offset.value != 0f) {
                val edge = if (offset.value > 0f) 1f else -1f
                val start = offset.value
                offset.animateTo(edge, ease) {
                    val a = value.absoluteValue
                    val frac = ((1f - a) / (1f - start.absoluteValue)).coerceIn(0f, 1f)
                    onProgress(start * frac)
                }
            }
            onBack()
            offset.snapTo(0f)
            onProgress(0f)
        } catch (e: CancellationException) {
            // The gesture was revoked before it crossed the threshold: ease
            // the surface back to rest while the preview rides with it frame by
            // frame, so the two return together instead of the preview freezing
            // at the last finger position and snapping once the glide ends.
            scope.launch {
                offset.animateTo(0f, ease) { onProgress(value) }
                onProgress(0f)
            }
            throw e
        }
    }
    Box(modifier.graphicsLayer {
        translationX = offset.value * size.width
        // A sheet in motion casts a shadow, like the system back does. Eased in
        // with the progress and gone at rest (zero elevation = no blur cost).
        shadowElevation = 14.dp.toPx() * offset.value.absoluteValue
        shape = RectangleShape
    }) {
        content()
    }
}