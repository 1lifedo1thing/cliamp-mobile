package stream.kleeamp.mobile.ui

/**
 * Shared ms -> "h:mm:ss"/"m:ss" clock, and large-number compaction. These were
 * duplicated verbatim across the player screen, the widget and the stations
 * list, so they now live in one place.
 */

/** `83_000` -> `1:23`; `3_700_000` -> `1:01:40`. */
internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** `1_200_000` -> `1.2m`; `50_000` -> `50.0k`; small counts stay plain. */
internal fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fm".format(n / 1_000_000f)
    n >= 1_000 -> "%.1fk".format(n / 1_000f)
    else -> n.toString()
}
