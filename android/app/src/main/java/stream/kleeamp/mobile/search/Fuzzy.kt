package stream.kleeamp.mobile.search

/**
 * A compact fuzzy matcher in the style of fzf/iTerm: the query characters must
 * appear in order (as a subsequence) in the haystack, and a LOWER score is
 * better. Consecutive runs are cheaper than spread-out matches, a leading/word
 * boundary match is cheaper than mid-word, so "aln" ranks above "ana" when
 * searching for "Alan Walker" even though both are subsequences.
 */
object Fuzzy {

    /** Positions (in [haystack]) that form the match, or null when no match. */
    fun match(query: String, haystack: String): List<Int>? {
        if (query.isEmpty()) return emptyList()
        val q = query.lowercase()
        val h = haystack.lowercase()
        if (q.length > h.length) return null

        val at = ArrayList<Int>(q.length)
        var qi = 0
        for (ci in h.indices) {
            if (qi < q.length && h[ci] == q[qi]) {
                at += ci
                qi++
            }
        }
        return if (qi == q.length) at else null
    }

    /** Lower is better. Int.MAX_VALUE means no match. */
    fun score(query: String, haystack: String): Int {
        val at = match(query, haystack) ?: return Int.MAX_VALUE
        var s = 0
        var prev = -2
        for (i in 0 until at.size) {
            val c = at[i]
            if (i > 0 && c == prev + 1) {
                // consecutive characters: free
            } else {
                s += (c - prev) // gap cost
            }
            prev = c
        }
        if (at[0] == 0) s -= 4                              // reward a prefix match
        val before = if (at[0] > 0) haystack[at[0] - 1] else ' '
        if (at[0] > 0 && (before == ' ' || before == '-' || before == '(')) s -= 2 // word boundary
        if (haystack.length <= 12) s -= 1
        return s
    }

    /** Char positions that matched, for highlight rendering. */
    fun matchedPositions(query: String, haystack: String): Set<Int>? =
        match(query, haystack)?.toSet()
}