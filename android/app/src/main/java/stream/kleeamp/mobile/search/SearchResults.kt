package stream.kleeamp.mobile.search

import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.servers.ProviderAccount

/** A single fuzzy result from any source, labelled with where it came from. */
sealed interface SearchHit {
    /** Plays this directly (an actual playable item). */
    val playable: Station?

    /** A stable key for LazyColumn. */
    val key: String

    /** Short origin tag shown on the trailing edge, e.g. "local"/"radio". */
    val origin: String

    /** The string we fuzzy-scored, used for hit-highlighting. */
    val haystack: String

    data class Song(val station: Station) : SearchHit {
        override val playable get() = station
        override val key get() = "song:${station.id}"
        override val origin get() = "local"
        override val haystack get() = "${station.name} ${station.artist}".trim()
    }

    data class StationHit(val station: Station, val fromProviders: Boolean = false) : SearchHit {
        override val playable get() = station
        override val key get() = "station:${station.url}"
        override val origin get() = if (fromProviders) "provider" else "radio"
        // Tags are the genre vocabulary of radio, so a station tagged "jazz"
        // surfaces when the query is "jazz" even though its name never says so.
        override val haystack get() =
            (listOf(station.name) + station.tagList).joinToString(" ")
    }

    data class Favorite(val station: Station) : SearchHit {
        override val playable get() = station
        override val key get() = "fav:${station.url}"
        override val origin get() = "fav"
        override val haystack get() =
            (listOf(station.name) + station.tagList).joinToString(" ")
    }

    /**
     * A recently played station. Never labelled "fav": the user did not star
     * it, they just heard it. (GlobalSearch used to wrap these as Favorite,
     * so recents wore the fav tag and filed under local.)
     */
    data class Recent(val station: Station) : SearchHit {
        override val playable get() = station
        override val key get() = "recent:${station.url}"
        override val origin get() = "recent"
        override val haystack get() =
            (listOf(station.name) + station.tagList).joinToString(" ")
    }

    /**
     * One episode out of a subscribed show's cached feed: plays immediately,
     * with the show's artwork behind it when the episode carries none.
     */
    data class Episode(val station: Station, val showTitle: String) : SearchHit {
        override val playable get() = station
        override val key get() = "episode:${station.id}"
        override val origin get() = "episode"
        override val haystack get() = "${station.name} $showTitle".trim()
    }

    data class Tag(val name: String, val count: Int) : SearchHit {
        override val playable get() = null
        override val key get() = "tag:#$name"
        override val origin get() = "#${name}"
        override val haystack get() = name
    }

    /**
     * A show you can open and browse the episodes of. Not playable itself - a
     * show is a feed, and which episode you wanted is the whole question.
     */
    data class Show(val show: PodcastShow, val subscribed: Boolean) : SearchHit {
        override val playable get() = null
        override val key get() = "show:${show.feedUrl}"
        override val origin get() = if (subscribed) "subscribed" else "podcast"
        override val haystack get() = "${show.title} ${show.author}".trim()
    }

    /** An account you can open and browse inside. */
    data class Provider(val account: ProviderAccount, val specLabel: String) : SearchHit {
        override val playable get() = null
        override val key get() = "provider:${account.id}"
        override val origin get() = specLabel
        override val haystack get() = "${account.label} $specLabel".trim()
    }
}

/** Result of scoring everything; sorted best-first. */
data class SearchResults(
    val hits: List<SearchHit>,
    val total: Int,
    val loading: Boolean = false,
)
