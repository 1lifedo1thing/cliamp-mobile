package stream.kleeamp.mobile.playback

import stream.kleeamp.mobile.model.Station

/**
 * The heard trail: actual play order this session, oldest to newest. Prev
 * and Next walk the loaded context; this stack is only their fallback while
 * nothing is loaded, plus what the UI consults for the transport state. The
 * source list a tap came from is the *context* (what Next walks); this stack
 * is what was genuinely heard. Recents stays a picker, never Up Next. Capped;
 * consecutive duplicates never append, which also makes every record site
 * safe to overlap (a tap plus the transition event for the same switch).
 */
internal class QueueHistory {
    /** Cap: a session log, not an archive; persisted recents owns depth. */
    private val past = ArrayDeque<Station>()
    private var pastIndex = -1

    companion object {
        private const val PAST_CAP = 100
    }

    val size: Int get() = synchronized(past) { past.size }
    val index: Int get() = synchronized(past) { pastIndex }

    /** Appends [station] unless it already tips the stack (re-seek, double record). */
    fun record(station: Station) = synchronized(past) {
        if (past.getOrNull(pastIndex)?.url == station.url) return@synchronized
        // A fresh play after stepping back forks: the redo tail is dropped.
        while (pastIndex < past.lastIndex) past.removeLast()
        past.addLast(station)
        pastIndex = past.lastIndex
        while (past.size > PAST_CAP) {
            past.removeFirst()
            pastIndex--
        }
    }

    /** Redo step for an empty source, or null when already at the tip. */
    fun forward(): Station? = synchronized(past) {
        if (pastIndex < past.lastIndex) past[++pastIndex] else null
    }

    /** Undo step for an empty source, or null when already at the start. */
    fun back(): Station? = synchronized(past) {
        if (pastIndex > 0) past[--pastIndex] else null
    }

    /**
     * Jump target for a history item without recording (the caller pre-stepped,
     * so the record is a duplicate by construction). Returns the absolute
     * index when the item lives in the current context, else null to start it
     * as a fresh single - a foreign context, same as tapping it would.
     */
    fun gotoTarget(station: Station, source: List<Station>, fallback: List<Station>): Int? {
        val src = source.ifEmpty { fallback }
        val abs = src.indexOfFirst { it.url == station.url }
        return abs.takeIf { it >= 0 && source.isNotEmpty() }
    }
}
