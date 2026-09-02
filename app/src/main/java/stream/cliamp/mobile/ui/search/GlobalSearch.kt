package stream.cliamp.mobile.ui.search

import stream.cliamp.mobile.data.NameCount
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.provider.ProviderAccount

/**
 * Fans the query out across every catalogue the app knows about - local songs,
 * favourites, the radio directory, providers - and returns the best fuzzy hits
 * best-first. One query finds things by any name everywhere.
 */
object GlobalSearch {

    fun run(
        query: String,
        localSongs: List<Station>,
        favorites: List<Station>,
        recent: List<Station>,
        radio: List<Station>,          // cliamp + directory stations combined
        tags: List<NameCount>,
        providers: List<ProviderAccount>,
    ): SearchResults {
        val term = query.trim()
        if (term.isBlank()) {
            val idle = ArrayList<SearchHit>()
            tags.take(12).forEach { idle += SearchHit.Tag(it.name, it.stationcount) }
            radio.take(20).forEach { idle += SearchHit.StationHit(it) }
            return SearchResults(idle, idle.size)
        }
        if (term.startsWith(":")) {
            val cmdHits = searchCommands.filter {
                it.syntax.startsWith(term.substringBefore(' '), ignoreCase = true)
            }
            return SearchResults(cmdHits, cmdHits.size)
        }

        val scored = ArrayList<Pair<Int, SearchHit>>()

        fun consider(hit: SearchHit) {
            val score = Fuzzy.score(term, hit.haystack)
            if (score != Int.MAX_VALUE) scored += score to hit
        }

        // Local songs, favourites and recent are all real files/streams; a given
        // url may sit in more than one bucket (a local song can be a favourite),
        // so dedupe by url keeping the most specific label.
        val byUrl = HashMap<String, SearchHit>()
        fun remember(hit: SearchHit) {
            val s = hit.playable ?: return
            val old = byUrl[s.url]
            // Prefer a Song label over a plain Favorite when both exist.
            if (old == null || old is SearchHit.Favorite && hit is SearchHit.Song) {
                byUrl[s.url] = hit
            }
        }
        localSongs.forEach { remember(SearchHit.Song(it)) }
        favorites.forEach { remember(SearchHit.Favorite(it)) }
        recent.forEach { remember(SearchHit.Favorite(it)) }
        byUrl.values.forEach { consider(it) }

        radio.forEach { s -> consider(SearchHit.StationHit(s)) }
        tags.forEach { t ->
            val sc = Fuzzy.score(term, t.name)
            if (sc != Int.MAX_VALUE) scored += sc to SearchHit.Tag(t.name, t.stationcount)
        }
        providers.forEach { p ->
            val sc = Fuzzy.score(term, p.label)
            if (sc != Int.MAX_VALUE) scored += sc to SearchHit.Provider(p, p.providerKey)
        }

        scored.sortWith(compareBy({ it.first }, { it.second.origin }))
        val hits = scored.map { it.second }
        return SearchResults(hits.take(240), hits.size)
    }
}