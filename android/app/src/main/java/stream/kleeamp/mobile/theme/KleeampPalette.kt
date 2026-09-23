package stream.kleeamp.mobile.theme

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
data class KleeampPalette(
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

val DarkPalette = KleeampPalette(
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
    inkTertiary = Color(0xFF878E88),
    inkFaint = Color(0xFF7C847D),
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

val LightPalette = KleeampPalette(
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
    inkTertiary = Color(0xFF5D635D),
    inkFaint = Color(0xFF666D67),
    accent = Color(0xFF007C2F),
    accentBright = Color(0xFF106B2C),
    accentBevel = Color(0xFF0D5C25),
    onAccent = Color(0xFFF4F8F5),
    accentWash = Color(0xFFCFEED2),
    amber = Color(0xFFA15900),
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

/**
 * Amber. Same construction as [DarkPalette] - every value is the dark
 * palette's own lightness and chroma, rotated to the accent's hue - so the
 * neutrals read faintly warm the way the dark palette's read faintly green.
 *
 * The requested #F9AA60 is, in oklch, the dark palette's semantic amber at the
 * same lightness and chroma and 16 degrees of hue apart. So the "remote / live
 * elsewhere" slot cannot lean on hue here and leans on the gold being duller
 * instead. retro-82 already ships with the two at an identical hue, so this is
 * a smaller collision than one already in the theme list.
 */
val AmberPalette = KleeampPalette(
    dark = true,
    // grounds
    canvas = Color(0xFF2D2824),
    ground = Color(0xFF100B07),
    groundScope = Color(0xFF0A0704),
    groundLock = Color(0xFF080502),
    panel = Color(0xFF17120D),
    panelRaised = Color(0xFF19120C),
    // lines
    hairline = Color(0xFF211A15),
    hairlineRegion = Color(0xFF29221D),
    frameBorder = Color(0xFF443B34),
    // ink
    ink = Color(0xFFF7F0EB),
    inkBright = Color(0xFFFAF4EF),
    inkSecondary = Color(0xFFAAA39E),
    inkTertiary = Color(0xFF928B86),
    inkFaint = Color(0xFF88807B),
    // accent
    accent = Color(0xFFF9AA60),
    accentBright = Color(0xFFFFC687),
    accentBevel = Color(0xFFB77534),
    onAccent = Color(0xFF210F01),
    accentWash = Color(0xFF2B1D10),
    // semantic
    amber = Color(0xFFEBB353),
    destructive = Color(0xFFBD423A),
    destructiveInk = Color(0xFFF27166),
    // controls
    keyFace = Color(0xFF1F1812),
    keyBorder = Color(0xFF3A312A),
    keyBevel = Color(0xFF0C0704),
    chipBorder = Color(0xFF302720),
    track = Color(0xFF2E2722),
    unlit = Color(0xFF2C231B),
    peak = Color(0xFFFAF4EF),
    // placeholder art stripes
    artA = Color(0xFF251C15),
    artB = Color(0xFF1B1611),
    artBorder = Color(0xFF352C25),
)

/**
 * Oxide. Rusted iron: the grounds carry twice the dark palette's chroma at the
 * red hue, so panels read as warm metal rather than neutral grey, and the ink
 * is the requested #F8E4D4 cream at 15.9:1 on the ground.
 *
 * The requested #7F2117 sits at oklch lightness 0.40, too dark to be the accent
 * itself: accent is a text colour here (selected rows, ACTIVE labels), and 0.40
 * against this ground is 2.4:1. It is the accent bevel instead, which is where
 * a deep edge tone belongs, and the accent is that same red lifted to 0.62 for
 * 5.0:1. Destructive stays the shared red every other theme uses, so it sits in
 * the accent's own family - as it already does in last-horizon.
 */
val OxidePalette = KleeampPalette(
    dark = true,
    // grounds
    canvas = Color(0xFF302725),
    ground = Color(0xFF120A08),
    groundScope = Color(0xFF0C0605),
    groundLock = Color(0xFF0A0403),
    panel = Color(0xFF1A100E),
    panelRaised = Color(0xFF1C100E),
    // lines
    hairline = Color(0xFF241816),
    hairlineRegion = Color(0xFF2C201E),
    frameBorder = Color(0xFF493936),
    // ink
    ink = Color(0xFFF8E4D4),
    inkBright = Color(0xFFF8E4D4),
    inkSecondary = Color(0xFFABA39E),
    inkTertiary = Color(0xFF938A85),
    inkFaint = Color(0xFF88807B),
    // accent
    accent = Color(0xFFD15D4D),
    accentBright = Color(0xFFE47C6C),
    accentBevel = Color(0xFF7F2117),
    onAccent = Color(0xFF240C09),
    accentWash = Color(0xFF2E1B17),
    // semantic
    amber = Color(0xFFEBB353),
    destructive = Color(0xFFBD423A),
    destructiveInk = Color(0xFFF27166),
    // controls
    keyFace = Color(0xFF231614),
    keyBorder = Color(0xFF3F2F2C),
    keyBevel = Color(0xFF0E0605),
    chipBorder = Color(0xFF352522),
    track = Color(0xFF312523),
    unlit = Color(0xFF2E211F),
    peak = Color(0xFFF8E4D4),
    // placeholder art stripes
    artA = Color(0xFF291A17),
    artB = Color(0xFF1E1412),
    artBorder = Color(0xFF3A2A27),
)

/**
 * Oxide, reversed. The icon canvas calls this ground "bone": the cream that is
 * ink in [OxidePalette] becomes the page here, and the oxide red that was only
 * a bevel there becomes the accent - #7F2117 sits at oklch lightness 0.40,
 * which is too dark to be an accent on a dark ground and right on a light one.
 *
 * Built off [LightPalette]'s lightness and chroma the way [OxidePalette] is
 * built off DarkPalette's, with two departures, both to land the requested
 * #F8E4D4 exactly on the ground: the light end of the ramp drops by 0.045 so
 * grounds, hairlines and key faces keep their original order instead of
 * inverting around a darker ground, and structural chroma is scaled to the
 * cream's own rather than doubled, which is what makes this bone rather than
 * the warm grey a smaller multiplier gives.
 */
val OxideLightPalette = KleeampPalette(
    dark = false,
    // grounds
    canvas = Color(0xFFDFCBBB),
    ground = Color(0xFFF8E4D4),
    groundScope = Color(0xFFF1DDCD),
    groundLock = Color(0xFFE9D5C5),
    panel = Color(0xFFF2D9C4),
    panelRaised = Color(0xFFF4D7BF),
    // lines
    hairline = Color(0xFFE3C4AB),
    hairlineRegion = Color(0xFFDABAA1),
    frameBorder = Color(0xFFCDA889),
    // ink
    ink = Color(0xFF251D1C),
    inkBright = Color(0xFF191110),
    inkSecondary = Color(0xFF554C4A),
    inkTertiary = Color(0xFF5F5655),
    inkFaint = Color(0xFF695F5F),
    // accent
    accent = Color(0xFF7F2117),
    accentBright = Color(0xFF5C1009),
    accentBevel = Color(0xFF4B0703),
    onAccent = Color(0xFFECE7E6),
    accentWash = Color(0xFFF5CAC3),
    // semantic
    amber = Color(0xFF875800),
    destructive = Color(0xFFBA2B28),
    destructiveInk = Color(0xFFA32320),
    // controls
    keyFace = Color(0xFFF8E4D4),
    keyBorder = Color(0xFFD9B495),
    keyBevel = Color(0xFFE3C4AB),
    chipBorder = Color(0xFFD9B495),
    track = Color(0xFFE3C4AB),
    unlit = Color(0xFFD8C6C3),
    peak = Color(0xFF251D1C),
    // placeholder art stripes
    artA = Color(0xFFF0D1B8),
    artB = Color(0xFFF9DCC4),
    artBorder = Color(0xFFD9B495),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }
