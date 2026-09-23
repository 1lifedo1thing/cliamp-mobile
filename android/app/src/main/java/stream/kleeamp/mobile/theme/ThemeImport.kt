package stream.kleeamp.mobile.theme

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
 * An optional `"name"` string labels the theme in Settings instead of
 * "custom"; blank or missing falls back to "custom".
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
fun parseCustomTheme(raw: String): Result<KleeampPalette> = runCatching {
    val obj = Json.parseToJsonElement(raw).jsonObject
    val dark = obj["dark"]?.jsonPrimitive?.booleanOrNull
        ?: throw IllegalArgumentException("missing \"dark\" (true/false)")
    val colors = CustomThemeRoles.associateWith { role ->
        val hex = obj[role]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("missing \"$role\"")
        parseHex(role, hex)
    }
    fun c(role: String): Color = colors.getValue(role)
    KleeampPalette(
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
fun decodeCustomThemeOrNull(raw: String): KleeampPalette? =
    raw.takeIf { it.isNotBlank() }?.let { parseCustomTheme(it).getOrNull() }

/**
 * The display name a theme file declares for itself, or null when absent.
 * Must be a string; blank trims to null so callers fall back to "custom".
 * Never fails: a broken file simply has no name.
 */
fun customThemeNameOrNull(raw: String): String? = runCatching {
    val primitive = Json.parseToJsonElement(raw).jsonObject["name"] as? JsonPrimitive
        ?: return@runCatching null
    // A JSON string prints quoted; numbers, booleans and null do not.
    if (!primitive.toString().startsWith("\"")) return@runCatching null
    primitive.content.trim().takeIf { it.isNotBlank() }
}.getOrNull()

/** `#RRGGBB` or `#AARRGGBB` into a Color, naming the role on failure. */
private fun parseHex(role: String, hex: String): Color {
    val h = hex.removePrefix("#")
    require(h.length == 6 || h.length == 8) { "bad colour for \"$role\": $hex" }
    val v = requireNotNull(h.toULongOrNull(16)) { "bad colour for \"$role\": $hex" }
    return Color(if (h.length == 6) (0xFF000000UL or v).toLong() else v.toLong())
}
