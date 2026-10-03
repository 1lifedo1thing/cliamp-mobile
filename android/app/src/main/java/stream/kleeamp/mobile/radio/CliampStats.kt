package stream.kleeamp.mobile.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.Request
import stream.kleeamp.mobile.net.Http

/**
 * Live "who's listening" figures for the cliamp channels, from the same two
 * statistics documents cliamp.stream renders:
 *
 * - `radio.cliamp.stream/statistics` (per-station listeners, per-country
 *   actives, all-time top countries plus the all-time peak)
 * - `radio.cliamp.stream/tracks/statistics` (playlist listeners)
 *
 * Aggregation mirrors the website exactly: per-station counts add both
 * sources, the country ranking uses live actives when any exist and falls
 * back to all-time sessions otherwise. [peak] stays in the model for
 * diagnostics but is never shown in station rows.
 */
data class CountryListeners(
    val code: String,
    val name: String,
    val listeners: Int,
)

data class CliampStats(
    val activeNow: Int,
    val peak: Int,
    val onPlaylists: Int = 0,
    val countries: List<CountryListeners> = emptyList(),
    val perStation: Map<String, Int> = emptyMap(),
    val isLive: Boolean = false,
)

/** Live listener count for one cliamp channel slug, or null before data lands. */
fun CliampStats.listenersFor(slug: String): Int? =
    if (perStation.isEmpty()) null else perStation[slug] ?: 0

/**
 * Adds one optimistic listener for [code]: bumps the row when present,
 * inserts it when absent. Rank order is restored by count, so the
 * leaderboard, bars and globe markers all follow. Null code is a no-op.
 */
internal fun boostCountryRows(rows: List<CountryListeners>, code: String?): List<CountryListeners> {
    if (code == null) return rows
    val out = if (rows.any { it.code == code }) {
        rows.map { if (it.code == code) it.copy(listeners = it.listeners + 1) else it }
    } else {
        rows + CountryListeners(code, countryDisplayName(code), 1)
    }
    return out.sortedByDescending { it.listeners }
}

suspend fun fetchCliampStats(): CliampStats? = withContext(Dispatchers.IO) {
    val main = runCatching {
        val req = Request.Builder().url(STATS_URL).header("User-Agent", Http.USER_AGENT).build()
        Http.artClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            r.body.string()
        }
    }.getOrNull() ?: return@withContext null
    val tracks = runCatching { Http.text(TRACK_STATS_URL) }.getOrNull()
    aggregateStats(main, tracks)
}

/**
 * Decodes the statistics documents; null when the main one is not one. Pure
 * so the website-identical aggregation is unit-tested on the JVM.
 */
internal fun parseStats(raw: String): CliampStats? = aggregateStats(raw, null)

internal fun aggregateStats(mainRaw: String, tracksRaw: String?): CliampStats? = runCatching {
    val main = Http.json.decodeFromString<StatsDoc>(mainRaw)
    val tracks = tracksRaw?.let { runCatching { Http.json.decodeFromString<TrackStatsDoc>(it) }.getOrNull() }

    val perStation = mutableMapOf<String, Int>()
    var now = 0
    var onPlaylists = 0
    val active = mutableMapOf<String, CountryListeners>()
    val allTime = mutableMapOf<String, CountryListeners>()

    main.stations.forEach { (slug, s) ->
        perStation[slug] = (perStation[slug] ?: 0) + s.activeListeners
        now += s.activeListeners
        s.activeCountries.forEach { c ->
            val n = c.count()
            if (c.code.isNotBlank() && n > 0) {
                val prev = active[c.code]
                active[c.code] = CountryListeners(
                    code = c.code,
                    name = c.country.ifBlank { prev?.name ?: c.code },
                    listeners = (prev?.listeners ?: 0) + n,
                )
            }
        }
        s.topCountries.forEach { c ->
            val n = c.count()
            if (c.code.isNotBlank() && n > 0) {
                val prev = allTime[c.code]
                allTime[c.code] = CountryListeners(
                    code = c.code,
                    name = c.country.ifBlank { prev?.name ?: c.code },
                    listeners = (prev?.listeners ?: 0) + n,
                )
            }
        }
    }
    tracks?.stations?.forEach { (slug, s) ->
        perStation[slug] = (perStation[slug] ?: 0) + s.activeListeners
        now += s.activeListeners
        onPlaylists += s.activeListeners
        s.activeCountries.forEach { c ->
            val n = c.count()
            if (c.code.isNotBlank() && n > 0) {
                val prev = active[c.code]
                active[c.code] = CountryListeners(
                    code = c.code,
                    name = c.country.ifBlank { prev?.name ?: c.code },
                    listeners = (prev?.listeners ?: 0) + n,
                )
            }
        }
    }

    val live = active.isNotEmpty()
    val rows = (if (live) active else allTime).values.sortedByDescending { it.listeners }
    CliampStats(
        activeNow = now,
        peak = main.peakListeners,
        onPlaylists = onPlaylists,
        countries = rows,
        perStation = perStation,
        isLive = live,
    )
}.getOrNull()

private const val STATS_URL = "https://radio.cliamp.stream/statistics"
private const val TRACK_STATS_URL = "https://radio.cliamp.stream/tracks/statistics"

@Serializable
private data class StatsDoc(
    @SerialName("peak_listeners") val peakListeners: Int = 0,
    val stations: Map<String, StationStats> = emptyMap(),
)

@Serializable
private data class StationStats(
    @SerialName("active_listeners") val activeListeners: Int = 0,
    @SerialName("active_listener_countries") val activeCountries: List<CountryEntry> = emptyList(),
    @SerialName("top_countries") val topCountries: List<CountryEntry> = emptyList(),
)

@Serializable
private data class TrackStatsDoc(
    val stations: Map<String, TrackStationStats> = emptyMap(),
)

@Serializable
private data class TrackStationStats(
    @SerialName("active_listeners") val activeListeners: Int = 0,
    @SerialName("active_listener_countries") val activeCountries: List<CountryEntry> = emptyList(),
)

/**
 * One country entry. The main document counts `sessions`, the playlist
 * document counts `listeners`; whichever is present wins.
 */
@Serializable
private data class CountryEntry(
    @SerialName("country_code") val code: String = "",
    val country: String = "",
    val sessions: Int = 0,
    val listeners: Int = 0,
) {
    fun count(): Int = if (sessions > 0) sessions else listeners
}
