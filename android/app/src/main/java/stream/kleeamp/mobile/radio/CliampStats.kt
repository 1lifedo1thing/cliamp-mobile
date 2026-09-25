package stream.kleeamp.mobile.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.Request
import stream.kleeamp.mobile.net.Http

/**
 * Live "who's listening" figures for the cliamp channels, from the same
 * statistics document cliamp.stream renders (`radio --stats` on desktop):
 * listeners connected now plus the all-time peak. Decorative traffic on
 * the tight-timeout art client; a dead endpoint simply yields null and the
 * row stays hidden.
 */
data class CliampStats(
    val activeNow: Int,
    val peak: Int,
)

suspend fun fetchCliampStats(): CliampStats? = withContext(Dispatchers.IO) {
    runCatching {
        val req = Request.Builder().url(STATS_URL).header("User-Agent", Http.USER_AGENT).build()
        Http.artClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            parseStats(r.body.string())
        }
    }.getOrNull()
}

/** Decodes the statistics document; null when it is not one. */
internal fun parseStats(raw: String): CliampStats? = runCatching {
    val doc = Http.json.decodeFromString<StatsDoc>(raw)
    CliampStats(
        activeNow = doc.stations.values.sumOf { it.activeListeners },
        peak = doc.peakListeners,
    )
}.getOrNull()

private const val STATS_URL = "https://radio.cliamp.stream/statistics"

@Serializable
private data class StatsDoc(
    @SerialName("peak_listeners") val peakListeners: Int = 0,
    val stations: Map<String, StationStats> = emptyMap(),
)

@Serializable
private data class StationStats(
    @SerialName("active_listeners") val activeListeners: Int = 0,
)
