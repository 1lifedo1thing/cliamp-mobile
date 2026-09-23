package stream.kleeamp.mobile.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono

/**
 * The one text input in the app. Styled to the concept, but text entry itself
 * belongs to the platform.
 *
 * The app used to draw its own on-screen keyboard, which looked right and cost
 * more than it looked: no URL or number layout without hand-building one, no
 * autofill or password managers, no access to the user's own IME, language,
 * autocorrect, dictation or clipboard, and nothing for an accessibility service
 * to talk to. BasicTextField keeps the hairline styling and hands all of that
 * back.
 */
@Composable
fun KleeampTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    textStyle: TextStyle = KleeampType.trackTitleCompact,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    autoCorrect: Boolean = false,
    autoFocus: Boolean = false,
    /**
     * Rows for a value that is not one line - a list of folders, a pasted
     * private key.
     */
    lines: Int = 1,
    onAction: () -> Unit = {},
) {
    val p = LocalPalette.current
    val requester = remember { FocusRequester() }

    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { requester.requestFocus() } }

    val multiline = lines > 1

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = !multiline,
        minLines = if (multiline) lines else 1,
        maxLines = if (multiline) Int.MAX_VALUE else 1,
        textStyle = textStyle.copy(color = p.ink),
        cursorBrush = SolidColor(p.accent),
        visualTransformation =
            if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else keyboardType,
            autoCorrectEnabled = autoCorrect,
            // A newline is the separator in a multi-line field, so the return
            // key has to stay a return key.
            imeAction = if (multiline) ImeAction.Default else imeAction,
        ),
        keyboardActions = KeyboardActions(
            onDone = { onAction() },
            onGo = { onAction() },
            onSearch = { onAction() },
            onSend = { onAction() },
            onNext = { onAction() },
        ),
        modifier = modifier.focusRequester(requester),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Mono(placeholder, textStyle, p.inkFaint, maxLines = if (multiline) lines else 1)
                }
                inner()
            }
        },
    )
}
