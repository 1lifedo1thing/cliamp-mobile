package stream.cliamp.mobile.ui.search

import stream.cliamp.mobile.data.PodcastShow
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.provider.ProviderAccount

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
        override val haystack get() = station.name
    }

    data class Favorite(val station: Station) : SearchHit {
        override val playable get() = station
        override val key get() = "fav:${station.url}"
        override val origin get() = "fav"
        override val haystack get() = station.name
    }

    data class Tag(val name: String, val count: Int) : SearchHit {
        override val playable get() = null
        override val key get() = "tag:#$name"
        override val origin get() = "#${name}"
        override val haystack get() = name
    }

    data class Command(val syntax: String, val hint: String, val takesArg: Boolean = false) : SearchHit {
        override val playable get() = null
        override val key get() = "cmd:$syntax"
        override val origin get() = "cmd"
        override val haystack get() = "$syntax $hint".trim()
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

/** Commands a fuzzy search can also return, as heads-up actions. */
val searchCommands = listOf(
    SearchHit.Command(":play", "tune the first hit for a name", takesArg = true),
    SearchHit.Command(":tag", "filter the directory by tag", takesArg = true),
    SearchHit.Command(":country", "filter the directory by iso code", takesArg = true),
    SearchHit.Command(":random", "tune anything at all"),
    SearchHit.Command(":fav", "star what is playing"),
    SearchHit.Command(":scope", "open scope and eq"),
    SearchHit.Command(":eq", "flat | rock | headphone", takesArg = true),
    SearchHit.Command(":settings", "playback, feel, storage"),
    SearchHit.Command(":clear", "empty the bar"),
)