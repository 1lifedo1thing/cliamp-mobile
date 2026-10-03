package stream.kleeamp.mobile.radio

/**
 * ISO 3166-1 alpha-2 to numeric country ids, the same identifiers the
 * cliamp.stream globe uses to join listener data onto world-atlas 110m
 * geometry. Every country the statistics endpoint can name must resolve;
 * unknown codes resolve to null and are skipped, never invented.
 */
internal val Alpha2ToNumeric: Map<String, Int> = mapOf(
    "AE" to 784, "AF" to 4, "AL" to 8, "AM" to 51, "AO" to 24, "AQ" to 10,
    "AR" to 32, "AT" to 40, "AU" to 36, "AZ" to 31, "BA" to 70, "BD" to 50,
    "BE" to 56, "BF" to 854, "BG" to 100, "BI" to 108, "BJ" to 204, "BN" to 96,
    "BO" to 68, "BR" to 76, "BS" to 44, "BT" to 64, "BW" to 72, "BY" to 112,
    "BZ" to 84, "CA" to 124, "CD" to 180, "CF" to 140, "CG" to 178, "CH" to 756,
    "CI" to 384, "CL" to 152, "CM" to 120, "CN" to 156, "CO" to 170, "CR" to 188,
    "CU" to 192, "CY" to 196, "CZ" to 203, "DE" to 276, "DJ" to 262, "DK" to 208,
    "DO" to 214, "DZ" to 12, "EC" to 218, "EE" to 233, "EG" to 818, "EH" to 732,
    "ER" to 232, "ES" to 724, "ET" to 231, "FI" to 246, "FJ" to 242, "FK" to 238,
    "FR" to 250, "GA" to 266, "GB" to 826, "GE" to 268, "GH" to 288, "GL" to 304,
    "GM" to 270, "GN" to 324, "GQ" to 226, "GR" to 300, "GT" to 320, "GW" to 624,
    "GY" to 328, "HN" to 340, "HR" to 191, "HT" to 332, "HU" to 348, "ID" to 360,
    "IE" to 372, "IL" to 376, "IN" to 356, "IQ" to 368, "IR" to 364, "IS" to 352,
    "IT" to 380, "JM" to 388, "JO" to 400, "JP" to 392, "KE" to 404, "KG" to 417,
    "KH" to 116, "KP" to 408, "KR" to 410, "KW" to 414, "KZ" to 398, "LA" to 418,
    "LB" to 422, "LK" to 144, "LR" to 430, "LS" to 426, "LT" to 440, "LU" to 442,
    "LV" to 428, "LY" to 434, "MA" to 504, "MD" to 498, "ME" to 499, "MG" to 450,
    "MK" to 807, "ML" to 466, "MM" to 104, "MN" to 496, "MR" to 478, "MW" to 454,
    "MX" to 484, "MY" to 458, "MZ" to 508, "NA" to 516, "NC" to 540, "NE" to 562,
    "NG" to 566, "NI" to 558, "NL" to 528, "NO" to 578, "NP" to 524, "NZ" to 554,
    "OM" to 512, "PA" to 591, "PE" to 604, "PG" to 598, "PH" to 608, "PK" to 586,
    "PL" to 616, "PR" to 630, "PS" to 275, "PT" to 620, "PY" to 600, "QA" to 634,
    "RO" to 642, "RS" to 688, "RU" to 643, "RW" to 646, "SA" to 682, "SB" to 90,
    "SD" to 729, "SE" to 752, "SI" to 705, "SK" to 703, "SL" to 694, "SN" to 686,
    "SO" to 706, "SR" to 740, "SS" to 728, "SV" to 222, "SY" to 760, "SZ" to 748,
    "TD" to 148, "TF" to 260, "TG" to 768, "TH" to 764, "TJ" to 762, "TL" to 626,
    "TM" to 795, "TN" to 788, "TR" to 792, "TT" to 780, "TW" to 158, "TZ" to 834,
    "UA" to 804, "UG" to 800, "US" to 840, "UY" to 858, "UZ" to 860, "VE" to 862,
    "VN" to 704, "VU" to 548, "YE" to 887, "ZA" to 710, "ZM" to 894, "ZW" to 716,
)

/** Zero-padded 3-digit world-atlas id for an alpha-2 code, or null. */
internal fun atlasIdFor(code: String): String? =
    Alpha2ToNumeric[code.uppercase()].let { n ->
        if (n == null) null else n.toString().padStart(3, '0')
    }

/**
 * Device country from the locale, validated against the atlas map. No
 * permission needed and works offline; unknown locales resolve to null
 * rather than guessing.
 */
fun deviceCountryCode(): String? = resolveCountryCode(java.util.Locale.getDefault().country)

internal fun resolveCountryCode(raw: String?): String? {
    val code = raw?.trim()?.uppercase()
    return if (code != null && code.length == 2 && Alpha2ToNumeric.containsKey(code)) code else null
}

/** English display name for an alpha-2 code, or the code itself. */
internal fun countryDisplayName(code: String): String =
    runCatching { java.util.Locale("", code).getDisplayCountry(java.util.Locale.ENGLISH) }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: code
