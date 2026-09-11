package stream.cliamp.mobile.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import stream.cliamp.mobile.data.db.CliampDatabase
import stream.cliamp.mobile.data.db.PlayStatEntity
import stream.cliamp.mobile.net.Http

/**
 * Local play counts plus ListenBrainz scrobbling, on the CLI's 50%-rule: a
 * music track counts once it has been heard for half its length or four
 * minutes, whichever comes first. Radio has no ends and episodes have resume
 * instead, so only local and provider tracks ever count.
 *
 * Counts live in Room (one row per URL) and feed the song info pane; the
 * scrobble itself is fire-and-forget with no retry queue - a dead network
 * drops that listen the way a missed analytics ping does. Last.fm needs an
 * API account plus a browser auth flow the app does not have yet, so
 * ListenBrainz (one pasted user token) is the only transport.
 */
class Scrobbler(
    context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val dao = CliampDatabase.get(context).stats()

    /** Half the length or four minutes, whichever is heard first. Unknown
     * lengths cannot do halves, so they take the full four minutes. */
    private val FOUR_MIN = 4 * 60 * 1000L

    private var trackedUrl: String? = null
    private var heardMs: Long = 0L
    private var lastTickMs: Long = 0L
    private var counted = false

    fun statsFor(url: String): Flow<PlayStatEntity?> = dao.stat(url)

    /**
     * Called from the player's poll with its clock. Accumulates heard time
     * while the same track keeps playing and counts it once the rule trips.
     */
    fun onTick(station: Station?, playing: Boolean, durationMs: Long) {
        val now = System.currentTimeMillis()
        if (!playing || station == null || !scrobblable(station)) {
            lastTickMs = now
            return
        }
        if (trackedUrl != station.url) {
            trackedUrl = station.url
            heardMs = 0L
            counted = false
        }
        if (!counted) {
            heardMs += (now - lastTickMs).coerceIn(0L, 5_000L)
            val need = if (durationMs > 0) minOf(durationMs / 2, FOUR_MIN) else FOUR_MIN
            if (heardMs >= need) {
                counted = true
                count(station)
            }
        }
        lastTickMs = now
    }

    private fun scrobblable(s: Station): Boolean =
        s.source == StationSource.Local || s.source == StationSource.Provider

    private fun count(station: Station) {
        scope.launch {
            val now = System.currentTimeMillis()
            runCatching { dao.record(station.url, now) }
            val token = runCatching { prefs.listenBrainzTokenSync() }.getOrNull().orEmpty()
            if (token.isBlank()) return@launch
            runCatching { postListen(token, station, now / 1000L) }
        }
    }

    private suspend fun postListen(token: String, s: Station, listenedAt: Long) {
        val body = Http.json.encodeToString(
            ListenPayload.serializer(),
            ListenPayload(
                listenType = "single",
                payload = listOf(
                    Listen(
                        listenedAt = listenedAt,
                        trackMetadata = TrackMetadata(
                            artistName = s.artist.ifBlank { "unknown artist" },
                            trackName = s.name.ifBlank { "untitled" },
                            releaseName = s.album.takeIf { it.isNotBlank() },
                        ),
                    ),
                ),
            ),
        )
        Http.postJson(
            "https://api.listenbrainz.org/1/submit-listens",
            body,
            mapOf("Authorization" to "Token $token"),
        )
    }
}

@kotlinx.serialization.Serializable
private data class ListenPayload(
    val listenType: String,
    val payload: List<Listen>,
)

@kotlinx.serialization.Serializable
private data class Listen(
    val listenedAt: Long,
    val trackMetadata: TrackMetadata,
)

@kotlinx.serialization.Serializable
private data class TrackMetadata(
    val artistName: String,
    val trackName: String,
    val releaseName: String? = null,
)
