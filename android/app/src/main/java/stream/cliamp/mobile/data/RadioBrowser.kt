package stream.cliamp.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import stream.cliamp.mobile.net.Http
import java.net.InetAddress
import java.net.URLEncoder

/**
 * The 50k+ community directory at radio-browser.info.
 *
 * The API has no single endpoint - it is a pool of mirrors behind
 * `all.api.radio-browser.info`. We resolve that name once, keep the reachable
 * mirror, and fail over to the next one on error, which is what their client
 * guidelines ask for.
 */
object RadioBrowser {

    private val fallbackMirrors = listOf(
        "https://de1.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
    )

    @Volatile private var mirrors: List<String> = fallbackMirrors
    @Volatile private var current: Int = 0

    private suspend fun discover() = withContext(Dispatchers.IO) {
        runCatching {
            val found = InetAddress.getAllByName("all.api.radio-browser.info")
                .mapNotNull { addr ->
                    runCatching { addr.canonicalHostName }.getOrNull()
                        ?.takeIf { it.endsWith("api.radio-browser.info") }
                }
                .distinct()
                .map { "https://$it" }
            if (found.isNotEmpty()) mirrors = found.shuffled()
        }
    }

    private suspend fun get(path: String): String {
        if (mirrors === fallbackMirrors) discover()
        var lastError: Throwable? = null
        repeat(mirrors.size) {
            val base = mirrors[(current + it) % mirrors.size]
            runCatching { return Http.text("$base$path") }
                .onFailure { e -> lastError = e }
            current = (current + 1) % mirrors.size
        }
        throw lastError ?: IllegalStateException("no radio-browser mirror reachable")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    suspend fun stats(): DirectoryStats? =
        runCatching { Http.json.decodeFromString<DirectoryStats>(get("/json/stats")) }.getOrNull()

    /** A page of the most-voted stations - the default directory ordering. */
    suspend fun topVoted(offset: Int, limit: Int = 60): List<Station> =
        decode(get("/json/stations/search?order=votes&reverse=true&hidebroken=true&limit=$limit&offset=$offset"))

    suspend fun trending(offset: Int, limit: Int = 60): List<Station> =
        decode(get("/json/stations/search?order=clicktrend&reverse=true&hidebroken=true&limit=$limit&offset=$offset"))

    suspend fun searchByName(query: String, offset: Int = 0, limit: Int = 60): List<Station> =
        decode(get("/json/stations/search?name=${enc(query)}&order=votes&reverse=true&hidebroken=true&limit=$limit&offset=$offset"))

    suspend fun byTag(tag: String, offset: Int = 0, limit: Int = 60): List<Station> =
        decode(get("/json/stations/search?tag=${enc(tag)}&order=votes&reverse=true&hidebroken=true&limit=$limit&offset=$offset"))

    suspend fun byCountryCode(cc: String, offset: Int = 0, limit: Int = 60): List<Station> =
        decode(get("/json/stations/search?countrycode=${enc(cc.uppercase())}&order=votes&reverse=true&hidebroken=true&limit=$limit&offset=$offset"))

    suspend fun byLanguage(lang: String, offset: Int = 0, limit: Int = 60): List<Station> =
        decode(get("/json/stations/search?language=${enc(lang)}&order=votes&reverse=true&hidebroken=true&limit=$limit&offset=$offset"))

    suspend fun topTags(limit: Int = 60): List<NameCount> =
        runCatching {
            Http.json.decodeFromString<List<NameCount>>(
                get("/json/tags?order=stationcount&reverse=true&hidebroken=true&limit=$limit")
            )
        }.getOrDefault(emptyList())

    /** Every country that exists in the directory, most stations first. */
    suspend fun topCountries(limit: Int? = null): List<CountryCount> =
        runCatching {
            val q = if (limit == null) "" else "&limit=$limit"
            Http.json.decodeFromString<List<CountryCount>>(
                get("/json/countries?order=stationcount&reverse=true$q")
            )
        }.getOrDefault(emptyList())

    /**
     * Report a play. The directory ranks stations by clicks, so skipping this
     * would quietly free-ride on everyone else's votes.
     */
    suspend fun reportClick(uuid: String) {
        if (uuid.isBlank()) return
        val base = mirrors.getOrNull(current) ?: return
        Http.ping("$base/json/url/$uuid")
    }

    private fun decode(body: String): List<Station> =
        Http.json.decodeFromString<List<RbStation>>(body)
            .asSequence()
            .filter { it.urlResolved.isNotBlank() || it.url.isNotBlank() }
            .filter { it.hls == 0 || it.urlResolved.endsWith(".m3u8") }
            .map { it.toStation() }
            .distinctBy { it.url }
            .toList()
}

@Serializable
private data class RbStation(
    val stationuuid: String = "",
    val name: String = "",
    val url: String = "",
    @SerialName("url_resolved") val urlResolved: String = "",
    val homepage: String = "",
    val favicon: String = "",
    val tags: String = "",
    val country: String = "",
    val countrycode: String = "",
    val language: String = "",
    val votes: Int = 0,
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Int = 0,
    val clickcount: Int = 0,
) {
    fun toStation() = Station(
        id = "rb:$stationuuid",
        name = name.trim().ifBlank { "unnamed station" },
        url = urlResolved.ifBlank { url },
        source = StationSource.Directory,
        tags = tags,
        country = country,
        countryCode = countrycode,
        codec = codec,
        bitrate = bitrate,
        votes = votes,
        homepage = homepage,
        favicon = favicon,
        uuid = stationuuid,
    )
}

@Serializable
data class DirectoryStats(
    val stations: Int = 0,
    @SerialName("stations_broken") val stationsBroken: Int = 0,
    val tags: Int = 0,
    val countries: Int = 0,
    val languages: Int = 0,
    @SerialName("clicks_last_hour") val clicksLastHour: Int = 0,
    @SerialName("clicks_last_day") val clicksLastDay: Int = 0,
) {
    val playable: Int get() = (stations - stationsBroken).coerceAtLeast(0)
}

@Serializable
data class NameCount(val name: String = "", val stationcount: Int = 0)

@Serializable
data class CountryCount(
    val name: String = "",
    val iso_3166_1: String = "",
    val stationcount: Int = 0,
)
