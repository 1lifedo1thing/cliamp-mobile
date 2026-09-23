package stream.kleeamp.mobile.search

import stream.kleeamp.mobile.radio.NameCount
import stream.kleeamp.mobile.podcasts.PodcastEpisode
import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.servers.ProviderAccount
import stream.kleeamp.mobile.podcasts.toStation

/**
 * Fans the query out across every catalogue the app knows about - local songs,
 * favourites, the radio directory, podcasts, providers - and returns the best
 * fuzzy hits best-first. One query finds things by any name everywhere.
 */
object GlobalSearch {

    /**
     * Dedupe order for one url across buckets: Song beats Favorite (starred),
     * Favorite beats Recent (merely heard).
     */
    private fun supersedes(old: SearchHit, hit: SearchHit): Boolean =
        old is SearchHit.Recent && hit !is SearchHit.Recent ||
            old is SearchHit.Favorite && hit is SearchHit.Song

    // Deterministic ranking pipeline; branch order is the ranking contract.
    // Deterministic ranking pipeline; branch order is the ranking contract.
    // Single deterministic entry over all corpora; splitting would scatter
    // the coupled scoring and dedup order.
    @Suppress("CyclomaticComplexMethod", "LongParameterList")
    fun run(
        query: String,
        localSongs: List<Station>,
        favorites: List<Station>,
        recent: List<Station>,
        radio: List<Station>,          // cliamp + directory stations combined
        tags: List<NameCount>,
        providers: List<ProviderAccount>,
        shows: List<PodcastShow> = emptyList(),
        subscribedFeeds: Set<String> = emptySet(),
        episodes: List<Pair<PodcastShow, PodcastEpisode>> = emptyList(),
    ): SearchResults {
        val term = query.trim()
        if (term.isBlank()) {
            val idle = ArrayList<SearchHit>()
            tags.take(12).forEach { idle += SearchHit.Tag(it.name, it.stationcount) }
            // Subscribed shows before the radio wall: with an empty bar they
            // are the shortest list here and the one you meant. Deduped by
            // feed like the scored path: the same show usually sits in both
            // halves, and twin keys crash the list.
            val seenIdleFeeds = HashSet<String>()
            shows.filter { it.feedUrl in subscribedFeeds }.take(8).forEach {
                if (!seenIdleFeeds.add(it.feedUrl)) return@forEach
                idle += SearchHit.Show(it, subscribed = true)
            }
            radio.take(20).forEach { idle += SearchHit.StationHit(it) }
            val deduped = idle.distinctBy { it.key }
            return SearchResults(deduped, deduped.size)
        }

        val scored = ArrayList<Pair<Int, SearchHit>>()

        fun consider(hit: SearchHit) {
            val score = Fuzzy.score(term, hit.haystack)
            if (score != Int.MAX_VALUE) scored += score to hit
        }

        // Local songs, favourites and recents are all real files/streams; a given
        // url may sit in more than one bucket (a local song can be a favourite),
        // so dedupe by url keeping the most specific label: Song, then
        // Favorite (the user starred it), then Recent (merely heard).
        val byUrl = HashMap<String, SearchHit>()
        fun remember(hit: SearchHit) {
            val s = hit.playable ?: return
            val old = byUrl[s.url]
            if (old == null || supersedes(old, hit)) {
                byUrl[s.url] = hit
            }
        }
        localSongs.forEach { remember(SearchHit.Song(it)) }
        favorites.forEach { remember(SearchHit.Favorite(it)) }
        recent.forEach { remember(SearchHit.Recent(it)) }
        byUrl.values.forEach { consider(it) }

        radio.forEach { s -> consider(SearchHit.StationHit(s)) }
        tags.forEach { t ->
            val sc = Fuzzy.score(term, t.name)
            if (sc != Int.MAX_VALUE) scored += sc to SearchHit.Tag(t.name, t.stationcount)
        }
        // Deduped by feed, because a subscribed show is usually also sitting in
        // the directory results the same query just fetched.
        val seenFeeds = HashSet<String>()
        shows.sortedByDescending { it.feedUrl in subscribedFeeds }.forEach { show ->
            if (!seenFeeds.add(show.feedUrl)) return@forEach
            consider(SearchHit.Show(show, subscribed = show.feedUrl in subscribedFeeds))
        }
        providers.forEach { p ->
            val sc = Fuzzy.score(term, p.label)
            if (sc != Int.MAX_VALUE) scored += sc to SearchHit.Provider(p, p.providerKey)
        }

        scored.sortWith(compareBy({ it.first }, { it.second.origin }))
        // Subscribed episodes answer "which installment" once the show and
        // song level already spoke, so they rank after everything else. The
        // url dedup above already claimed favourited episodes for their own
        // buckets; only genuinely new ones land here.
        val knownUrls = byUrl.keys
        val epScored = ArrayList<Pair<Int, SearchHit>>()
        episodes.forEach { (show, ep) ->
            val station = ep.toStation(show)
            if (station.url in knownUrls) return@forEach
            val score = Fuzzy.score(term, "${ep.title} ${ep.description} ${show.title}")
            if (score != Int.MAX_VALUE) epScored += score to SearchHit.Episode(station, show.title)
        }
        epScored.sortBy { it.first }
        // Keys feed a keyed LazyColumn, so uniqueness is load-bearing: first
        // wins. The per-source dedups above already keep the _right_ copy
        // (subscribed shows, Song over Favorite); this only guarantees the
        // invariant against sources meeting for the first time.
        val hits = (scored.map { it.second } + epScored.map { it.second }).distinctBy { it.key }
        return SearchResults(hits.take(240), hits.size)
    }
}
