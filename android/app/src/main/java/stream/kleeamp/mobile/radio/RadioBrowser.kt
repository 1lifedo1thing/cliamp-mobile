package stream.kleeamp.mobile.radio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import stream.kleeamp.mobile.net.Http
import java.net.InetAddress
import java.net.URLEncoder
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

/**
 * The 50k+ community directory at radio-browser.info.
 *
 * The API has no single endpoint - it is a pool of mirrors behind
 * `all.api.radio-browser.info`. We resolve that name once, keep the reachable
 * mirror, and fail over to the next one on error, which is what their client
 * guidelines ask for. The last mirror that answered is pinned to disk, so a
 * cold start skips name resolution and probing altogether and talks to the
 * mirror that worked last time.
 */
object RadioBrowser {
    /** Shared ordering for the directory search endpoints. */
    private const val SEARCH_ORDER = "order=votes&reverse=true&hidebroken=true"
    private val fallbackMirrors = listOf(
        "https://de1.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
    )

    @Volatile private var mirrors: List<String> = emptyList()
    @Volatile private var dead: Set<String> = emptySet()

    private val mirrorLock = Mutex()

    /** Where the last live mirror is remembered for the next launch. */
    @Volatile private var cacheFile: java.io.File? = null

    fun init(context: Context) {
        cacheFile = java.io.File(context.cacheDir, "rbmirror.txt")
        // A pinned mirror replaces discovery for the whole first page: start
        // from it and only re-probe the field when it turns out to be gone.
        mirrors = loadPinned()?.let { listOf(it) } ?: emptyList()
    }

    private fun loadPinned(): String? =
        cacheFile?.takeIf { it.isFile }?.readText()?.trim()
            ?.takeIf { it.startsWith("https://") && "api.radio-browser.info" in it }

    private fun remember(base: String) {
        val f = cacheFile ?: return
        runCatching { f.parentFile?.mkdirs(); f.writeText(base) }
    }

    private suspend fun discover() = withContext(Dispatchers.IO) {
        var found: List<String> = emptyList()
        runCatching {
            found = InetAddress.getAllByName("all.api.radio-browser.info")
                .mapNotNull { addr ->
                    runCatching { addr.canonicalHostName }.getOrNull()
                        ?.takeIf { it.endsWith("api.radio-browser.info") }
                }
                .distinct()
                .map { "https://$it" }
        }
        // Resolution failing is usually the same network trouble, so probe the
        // known mirrors in its place rather than trusting an offline list.
        if (found.isEmpty()) found = fallbackMirrors
        // Probe everything at once: three mirrors probed one after another
        // would wait out two dead ones' timeouts before the first page could
        // even start. Each probe is capped at two seconds, so the whole sweep
        // is two seconds, never six.
        val pinned = loadPinned()
        val reachable = coroutineScope {
            buildList {
                pinned?.let { add(it) }
                addAll(found)
            }.distinct().map { c -> async { c to alive(c) } }.awaitAll()
                .filter { (_, ok) -> ok }
                .map { (c, _) -> c }
        }
        // Nothing answered: leave an empty list so the call fails fast and the
        // screen can offer a manual retry, instead of silently waiting out
        // timeouts against a mirror that cannot be reached.
        if (reachable.isEmpty()) {
            mirrors = emptyList()
        } else {
            // The pinned mirror leads so requests keep going where they were
            // until it actually stops answering.
            mirrors = buildList {
                pinned?.takeIf { it in reachable }?.let { add(it) }
                addAll(reachable.shuffled())
            }.distinct()
        }
        // A fresh probe supersedes the mid-session dead list.
        dead = emptySet()
    }

    /** True when the mirror accepts a TCP connection within a moment. */
    private fun alive(https: String): Boolean =
        runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(https.removePrefix("https://"), 443), 2000) }
            true
        }.getOrDefault(false)

    // Any mirror failure moves to the next mirror by design; narrowing the
    // catch would let new failure modes skip failover and surface instead.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun get(path: String): String {
        // An emptied mirror list is a total outage from the last probe; retry
        // re-probes rather than trusting it, so a manual "try again" after the
        // network returns actually reaches a live mirror again.
        mirrorLock.withLock { if (mirrors.isEmpty()) discover() }
        var lastError: Throwable? = null
        repeat(2) { pass ->
            val remaining = mirrors.filterNot { it in dead }
            for (base in remaining) {
                try {
                    val body = Http.text("$base$path")
                    // The mirror that actually answered is the one worth
                    // remembering for next launch.
                    remember(base)
                    return body
                } catch (e: Exception) {
                    lastError = e
                    // Once a mirror has burned its timeout it usually stays dead
                    // for this session; skip it from here on so later pages never
                    // pay the same wait again.
                    dead = dead + base
                }
            }
            // The first pass may have been a single pinned mirror that went
            // stale since the last session; probe the field live and trail the
            // fresh mirror list once before giving up.
            if (pass == 0) mirrorLock.withLock { discover() }
        }
        throw lastError ?: error("no radio-browser mirror reachable")
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
        decode(get("/json/stations/search?name=${enc(query)}&$SEARCH_ORDER&limit=$limit&offset=$offset"))

    suspend fun byTag(tag: String, offset: Int = 0, limit: Int = 60): List<Station> =
        decode(get("/json/stations/search?tag=${enc(tag)}&$SEARCH_ORDER&limit=$limit&offset=$offset"))

    suspend fun byCountryCode(cc: String, offset: Int = 0, limit: Int = 60): List<Station> =
        decode(
            get("/json/stations/search?countrycode=${enc(cc.uppercase())}&$SEARCH_ORDER&limit=$limit&offset=$offset")
        )

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
        if (mirrors.isEmpty()) discover()
        val base = mirrors.firstOrNull() ?: return
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
    @SerialName("iso_3166_1") val iso31661: String = "",
    val stationcount: Int = 0,
)
