package stream.kleeamp.mobile.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * We ride on MaterialTheme only far enough to keep m3 components (ripples,
 * text selection) coherent. All real styling comes from [LocalPalette] and
 * [KleeampType] - the concept has no Material surfaces, elevation or shape scale.
 */
/** Read by every mechanical control so one switch silences the whole app. */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/**
 * The concept's corner radii, one scale. Small slots (covers, handles, badges)
 * sit at [tiny]; the chip/menu/back language at [small]; plates, tiles and
 * cards at [medium]; the hero player plate at [large]; the transport keys
 * keep their own harder [key] radius.
 */
object KleeampShape {
    val tiny = 4.dp
    val small = 6.dp
    val medium = 8.dp
    val large = 20.dp
    val key = 11.dp
}

/**
 * Resolves the stored theme preference.
 *
 * "system" is oxide, light or dark to match the device, and it is the default -
 * oxide is the app's identity now, and the launcher icon is built from the same
 * two colours. The original phosphor-green pair is still here as "dark" and
 * "light" for anyone who wants it, just no longer what you get by default.
 *
 * Anything not listed is an Omarchy theme key, and an unknown key falls back
 * rather than crashing, so a theme removed from the machine does not brick the
 * app.
 */
fun paletteFor(preference: String, systemDark: Boolean, custom: KleeampPalette? = null): KleeampPalette = when (preference) {
    "system" -> if (systemDark) OxidePalette else OxideLightPalette
    "oxide" -> OxidePalette
    "oxide-light" -> OxideLightPalette
    "dark" -> DarkPalette
    "light" -> LightPalette
    "amber" -> AmberPalette
    "custom" -> custom ?: if (systemDark) OxidePalette else OxideLightPalette
    else -> OmarchyPalettes[preference]
        ?: if (systemDark) OxidePalette else OxideLightPalette
}

@Composable
fun KleeampTheme(
    palette: KleeampPalette = DarkPalette,
    haptics: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = palette.dark
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.ground,
            onBackground = palette.ink,
            surface = palette.panel,
            onSurface = palette.ink,
            error = palette.destructive,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.ground,
            onBackground = palette.ink,
            surface = palette.panel,
            onSurface = palette.ink,
            error = palette.destructive,
        )
    }

    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalHapticsEnabled provides haptics,
        LocalContentColor provides palette.ink,
        LocalTextStyle provides KleeampType.rowPrimary,
        LocalIndication provides ripple(color = palette.accent),
    ) {
        MaterialTheme(colorScheme = scheme) {
            Box(Modifier.fillMaxSize().background(palette.ground)) { content() }
        }
    }
}

/** Terse text helper: the style carries its own family, so style + colour is all we pass. */
@Composable
fun Mono(
    text: String,
    style: TextStyle,
    color: Color = LocalContentColor.current,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) = Text(
    text = text,
    style = style,
    color = color,
    modifier = modifier,
    maxLines = maxLines,
    overflow = overflow,
)
