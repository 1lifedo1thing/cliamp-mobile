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

}
