package stream.cliamp.mobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Every colour here is a straight sRGB conversion of an oklch() value from the
 * concept. The hue never leaves the 148-150 family for neutrals, so the greys
 * read faintly green. Amber means "remote / live elsewhere" and nothing else.
 * Red is only ever destructive.
 */
@Immutable
data class CliampPalette(
    val dark: Boolean,
    // grounds
    val canvas: Color,
    val ground: Color,
    val groundScope: Color,
    val groundLock: Color,
    val panel: Color,
    val panelRaised: Color,
    // lines
    val hairline: Color,
    val hairlineRegion: Color,
    val frameBorder: Color,
    // ink
    val ink: Color,
    val inkBright: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    val inkFaint: Color,
    // accent
    val accent: Color,
    val accentBright: Color,
    val accentBevel: Color,
    val onAccent: Color,
    val accentWash: Color,
    // semantic
    val amber: Color,
    val destructive: Color,
    val destructiveInk: Color,
    // controls
    val keyFace: Color,
    val keyBorder: Color,
    val keyBevel: Color,
    val chipBorder: Color,
    val track: Color,
    val unlit: Color,
    val peak: Color,
    // placeholder art stripes
    val artA: Color,
    val artB: Color,
    val artBorder: Color,
)

val DarkPalette = CliampPalette(
    dark = true,
    canvas = Color(0xFF272A27),
    ground = Color(0xFF0A0D0A),
    groundScope = Color(0xFF060806),
    groundLock = Color(0xFF040604),
    panel = Color(0xFF101411),
    panelRaised = Color(0xFF101511),
    hairline = Color(0xFF191D19),
    hairlineRegion = Color(0xFF212521),
    frameBorder = Color(0xFF393F3A),
    ink = Color(0xFFEDF4EE),
    inkBright = Color(0xFFF1F7F2),
    inkSecondary = Color(0xFFA0A7A1),
    inkTertiary = Color(0xFF7B827C),
    inkFaint = Color(0xFF6A716B),
    accent = Color(0xFF73E889),
    accentBright = Color(0xFF9BFFAB),
    accentBevel = Color(0xFF44AA5A),
    onAccent = Color(0xFF061909),
    accentWash = Color(0xFF13251A),
    amber = Color(0xFFEBB353),
    destructive = Color(0xFFBD423A),
    destructiveInk = Color(0xFFF27166),
    keyFace = Color(0xFF161B17),
    keyBorder = Color(0xFF2F3530),
    keyBevel = Color(0xFF060907),
    chipBorder = Color(0xFF252B26),
    track = Color(0xFF262A26),
    unlit = Color(0xFF1E2820),
    peak = Color(0xFFF1F7F2),
    artA = Color(0xFF1A201B),
    artB = Color(0xFF141815),
    artBorder = Color(0xFF2A302B),
)

val LightPalette = CliampPalette(
    dark = false,
    canvas = Color(0xFFDBDFDC),
    ground = Color(0xFFF4F8F5),
    groundScope = Color(0xFFEDF1EE),
    groundLock = Color(0xFFE5E9E6),
    panel = Color(0xFFE9EEEA),
    panelRaised = Color(0xFFE8EDE8),
    hairline = Color(0xFFD5DBD6),
    hairlineRegion = Color(0xFFCBD1CC),
    frameBorder = Color(0xFFB9C0BA),
    ink = Color(0xFF1B211C),
    inkBright = Color(0xFF11150F),
    inkSecondary = Color(0xFF545A54),
    inkTertiary = Color(0xFF7B827C),
    inkFaint = Color(0xFF8A918B),
    accent = Color(0xFF18883A),
    accentBright = Color(0xFF106B2C),
    accentBevel = Color(0xFF0D5C25),
    onAccent = Color(0xFFF4F8F5),
    accentWash = Color(0xFFCFEED2),
    amber = Color(0xFFB0660C),
    destructive = Color(0xFFBA2B28),
    destructiveInk = Color(0xFFA32320),
    keyFace = Color(0xFFF4F8F5),
    keyBorder = Color(0xFFC5CCC6),
    keyBevel = Color(0xFFD5DBD6),
    chipBorder = Color(0xFFC5CCC6),
    track = Color(0xFFD5DBD6),
    unlit = Color(0xFFD0DDD2),
    peak = Color(0xFF1B211C),
    artA = Color(0xFFE2E8E3),
    artB = Color(0xFFEDF2ED),
    artBorder = Color(0xFFC5CCC6),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }
