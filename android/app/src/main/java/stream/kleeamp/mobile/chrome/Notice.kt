package stream.kleeamp.mobile.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/** A transient bottom-center message: text plus its severity. */
data class Notice(
    val id: Long,
    val text: String,
    val error: Boolean = false,
)

/**
 * In-process bus for one-shot notices (playlist import results and the
 * like). Each [show] mints a new id so back-to-back identical messages
 * still retrigger the host; the host owns the 2.5s auto-dismiss.
 */
object NoticeBus {
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()
    private var nextId = 0L

    fun show(text: String, error: Boolean = false) {
        _notice.value = Notice(++nextId, text, error)
    }
}

/**
 * The global notice pill: the same bottom-center language as
 * [QueueConfirmHost], accent for confirmations and destructive red for
 * errors. Pops in, holds 2.5s, fades. Touch-transparent and decorative
 * to accessibility.
 */
@Composable
fun NoticeHost(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val notice by NoticeBus.notice.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(notice) {
        val current = notice ?: return@LaunchedEffect
        visible = true
        delay(2500)
        if (NoticeBus.notice.value?.id == current.id) visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.9f, animationSpec = tween(150)),
        exit = fadeOut(tween(400)),
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(KleeampShape.small))
                    .background(if (notice?.error == true) p.destructive else p.accent)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .clearAndSetSemantics {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mono(notice?.text.orEmpty(), KleeampType.chip, p.onAccent)
            }
        }
    }
}
