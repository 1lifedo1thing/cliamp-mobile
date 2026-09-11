package stream.cliamp.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Custom theme import: a JSON file mapping every palette role to a hex
 * colour, plus the mode flag. The contract is the whole contract - a theme
 * that only supplies a ground and an accent would leave bevels and meter
 * cells sitting on it as grey overlay, which is exactly the failure the
 * palette roles exist to prevent.
 *
 * Shape: `{"dark": true, "canvas": "#...", "ground": "#...", ...}` with all
 * [CustomThemeRoles] present. Unknown extra keys are tolerated and ignored.
 */
val CustomThemeRoles = listOf(
    "canvas", "ground", "groundScope", "groundLock", "panel", "panelRaised",
    "hairline", "hairlineRegion", "frameBorder",
    "ink", "inkBright", "inkSecondary", "inkTertiary", "inkFaint",
    "accent", "accentBright", "accentBevel", "onAccent", "accentWash",
    "amber", "destructive", "destructiveInk",
    "keyFace", "keyBorder", "keyBevel", "chipBorder", "track", "unlit", "peak",
    "artA", "artB", "artBorder",
)

/** Parses an imported theme file, naming the first missing or bad role. */
fun parseCustomTheme(raw: String): Result<CliampPalette> = runCatching {
    val obj = Json.parseToJsonElement(raw).jsonObject
    val dark = obj["dark"]?.jsonPrimitive?.booleanOrNull
        ?: throw IllegalArgumentException("missing \"dark\" (true/false)")
    val colors = CustomThemeRoles.associateWith { role ->
        val hex = obj[role]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("missing \"$role\"")
        parseHex(role, hex)
    }
    fun c(role: String): Color = colors.getValue(role)
    CliampPalette(
        dark = dark,
        canvas = c("canvas"), ground = c("ground"), groundScope = c("groundScope"),
        groundLock = c("groundLock"), panel = c("panel"), panelRaised = c("panelRaised"),
        hairline = c("hairline"), hairlineRegion = c("hairlineRegion"), frameBorder = c("frameBorder"),
        ink = c("ink"), inkBright = c("inkBright"), inkSecondary = c("inkSecondary"),
        inkTertiary = c("inkTertiary"), inkFaint = c("inkFaint"),
        accent = c("accent"), accentBright = c("accentBright"), accentBevel = c("accentBevel"),
        onAccent = c("onAccent"), accentWash = c("accentWash"),
        amber = c("amber"), destructive = c("destructive"), destructiveInk = c("destructiveInk"),
        keyFace = c("keyFace"), keyBorder = c("keyBorder"), keyBevel = c("keyBevel"),
        chipBorder = c("chipBorder"), track = c("track"), unlit = c("unlit"), peak = c("peak"),
        artA = c("artA"), artB = c("artB"), artBorder = c("artBorder"),
    )
}

/** Decodes a stored custom theme, or null when absent or broken. */
fun decodeCustomThemeOrNull(raw: String): CliampPalette? =
    raw.takeIf { it.isNotBlank() }?.let { parseCustomTheme(it).getOrNull() }

/** `#RRGGBB` or `#AARRGGBB` into a Color, naming the role on failure. */
private fun parseHex(role: String, hex: String): Color {
    val h = hex.removePrefix("#")
    if (h.length != 6 && h.length != 8) throw IllegalArgumentException("bad colour for \"$role\": $hex")
    val v = h.toULongOrNull(16) ?: throw IllegalArgumentException("bad colour for \"$role\": $hex")
    return Color(if (h.length == 6) (0xFF000000UL or v).toLong() else v.toLong())
}
