package stream.cliamp.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import stream.cliamp.mobile.net.Http

/**
 * cliamp's own stations. The twelve below are the offline seed - the same list
 * `cliamp` ships in main.go - and they are replaced at launch by whatever
 * `streams.m3u` currently advertises, so a new channel appears without a
 * client release.
 */
object CliampRadio {

    const val BASE = "https://radio.cliamp.stream"
    const val PLAYLIST_URL = "$BASE/streams.m3u"
    const val STATS_URL = "$BASE/statistics"

    private fun seed(slug: String, name: String) = Station(
        id = "cliamp:$slug",
        name = name,
        url = "$BASE/$slug/stream",
        source = StationSource.Cliamp,
        slug = slug,
        codec = "MP3",
    )

    val builtin: List<Station> = listOf(
        seed("lofi", "Lofi"),
        seed("synthwave", "Synthwave"),
        seed("edm", "EDM"),
        seed("omarchy", "Omarchy"),
        seed("ncs", "NCS"),
        seed("ncs-house", "NCS House"),
        seed("ncs-dubstep", "NCS Dubstep"),
        seed("ncs-dnb", "NCS Drum & Bass"),
        seed("ncs-trap", "NCS Trap"),
        seed("ncs-phonk", "NCS Phonk"),
        seed("ncs-pop", "NCS Pop"),
        seed("ncs-chill", "NCS Chill"),
    )

    /** Parse `#EXTINF:-1,Name` / url pairs out of the live m3u. */
    fun parseM3u(body: String): List<Station> {
        val out = mutableListOf<Station>()
        var pending: String? = null
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line == "#EXTM3U" -> Unit
                line.startsWith("#EXTINF") -> pending = line.substringAfter(',', "").trim().ifBlank { null }
                line.startsWith("#") -> Unit
                else -> {
                    val slug = line.trimEnd('/').substringBeforeLast("/stream", "")
                        .substringAfterLast('/')
                        .ifBlank { line.substringAfterLast('/') }
                    out += Station(
                        id = "cliamp:${slug.ifBlank { line.hashCode().toString() }}",
                        name = pending ?: slug.replace('-', ' '),
                        url = line,
                        source = StationSource.Cliamp,
                        slug = slug,
                        codec = "MP3",
                    )
                    pending = null
                }
            }
        }
        return out
    }

    suspend fun fetchStations(): List<Station> =
        runCatching { parseM3u(Http.text(PLAYLIST_URL)) }
            .getOrDefault(emptyList())
            .ifEmpty { builtin }

    suspend fun fetchStats(): CliampStats? =
        runCatching { Http.json.decodeFromString<CliampStats>(Http.text(STATS_URL)) }.getOrNull()
}

@Serializable
data class CliampStats(
    @SerialName("total_sessions") val totalSessions: Long = 0,
    @SerialName("total_listen_hours") val totalListenHours: Double = 0.0,
    @SerialName("peak_listeners") val peakListeners: Int = 0,
    val stations: Map<String, StationStats> = emptyMap(),
) {
    val activeListeners: Int get() = stations.values.sumOf { it.activeListeners }
}

@Serializable
data class StationStats(
    @SerialName("total_sessions") val totalSessions: Long = 0,
    @SerialName("total_listen_hours") val totalListenHours: Double = 0.0,
    @SerialName("peak_listeners") val peakListeners: Int = 0,
    @SerialName("active_listeners") val activeListeners: Int = 0,
    @SerialName("active_listener_countries") val activeCountries: List<CountryStat> = emptyList(),
    @SerialName("top_countries") val topCountries: List<CountryStat> = emptyList(),
    @SerialName("top_cities") val topCities: List<CityStat> = emptyList(),
    val daily: List<DailyStat> = emptyList(),
)

@Serializable
data class CountryStat(
    val country: String = "",
    @SerialName("country_code") val countryCode: String = "",
    val sessions: Long = 0,
    @SerialName("listen_hours") val listenHours: Double = 0.0,
)

@Serializable
data class CityStat(
    val city: String = "",
    @SerialName("country_code") val countryCode: String = "",
    val sessions: Long = 0,
    @SerialName("listen_hours") val listenHours: Double = 0.0,
)

@Serializable
data class DailyStat(
    val date: String = "",
    val sessions: Long = 0,
    @SerialName("listen_hours") val listenHours: Double = 0.0,
)
