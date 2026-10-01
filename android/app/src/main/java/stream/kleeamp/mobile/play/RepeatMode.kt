package stream.kleeamp.mobile.play

/**
 * Repeat mode, cycling off → all → one like cliamp. Persisted as a lowercase
 * string in prefs (`off`/`all`/`one`, matching cliamp's config values).
 */
enum class RepeatMode(val key: String) {
    Off("off"),
    All("all"),
    One("one"),
    ;

    /** Next step of the cycle. */
    fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(key: String?): RepeatMode =
            entries.firstOrNull { it.key == key } ?: Off
    }
}

/**
 * Repeat-all wrap for a clamped step: a Linear step that went nowhere (prev
 * at the head, next at the tail) wraps around the list instead.
 * Single-item lists and no-ops stay put.
 */
fun repeatAllTarget(srcSize: Int, here: Int, delta: Int): Int {
    if (srcSize <= 1 || delta == 0) return here
    return ((here + delta) % srcSize + srcSize) % srcSize
}
