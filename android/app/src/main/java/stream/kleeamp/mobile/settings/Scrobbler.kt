package stream.kleeamp.mobile.settings

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import stream.kleeamp.mobile.db.KleeampDatabase
import stream.kleeamp.mobile.db.PlayStatEntity
import stream.kleeamp.mobile.db.ScrobbleEntity
import stream.kleeamp.mobile.net.Http
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.prefs.Prefs

    /** Half the length or four minutes, whichever is heard first. Unknown
     * lengths cannot do halves, so they take the full four minutes. */
private const val FOUR_MIN = 4 * 60 * 1000L

/** Outbox rows older than this are pruned instead of retried forever. */
private const val MAX_ROW_AGE_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Local play counts plus ListenBrainz scrobbling, on the CLI's 50%-rule: a
 * music track counts once it has been heard for half its length or four
 * minutes, whichever comes first.
 *
 * Counts live in Room (one row per URL) and feed the song info pane;
 * scrobbles go through a persisted outbox instead of fire-and-forget, so a
 * dead network delays a listen instead of dropping it. The drain runs at
 * startup and after every count, with backoff per row.
 *
 * Radio has no ends, so it scrobbles per stream title - and only when the
 * user opts in (`scrobbleRadio`, off by default): every title change would
 * otherwise spam listens the listener never chose. Last.fm needs an API
 * account plus a browser auth flow the app does not have yet, so
 * ListenBrainz (one pasted user token) is the only transport.
 */
class Scrobbler(
    context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val dao = KleeampDatabase.get(context).stats()
    private val outbox = KleeampDatabase.get(context).scrobbles()

    private var trackedKey: String? = null
    private var heardMs: Long = 0L
    private var lastTickMs: Long = 0L
    private var counted = false

    /** Cached off the prefs flow; the UI thread never waits on DataStore. */
    private var radioOn = false

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Unsent listens, for the Settings status line. */
    val pendingCount: Flow<Int> = outbox.count()

    private val drainMutex = Mutex()

    init {
        scope.launch { prefs.scrobbleRadio.collect { radioOn = it } }
        scope.launch { drain() }
    }

    fun statsFor(url: String): Flow<PlayStatEntity?> = dao.stat(url)

    /**
     * Called from the player's poll with its clock. Accumulates heard time
     * while the same track (or the same radio title) keeps playing and
     * counts it once the rule trips.
     */
    fun onTick(station: Station?, playing: Boolean, durationMs: Long, streamTitle: String = "") {
        val now = System.currentTimeMillis()
        if (!playing || station == null) {
            lastTickMs = now
            return
        }
        val key = trackKey(station, streamTitle)
        if (key == null) {
            lastTickMs = now
            return
        }
        if (trackedKey != key) {
            trackedKey = key
            heardMs = 0L
            counted = false
        }
        if (!counted) {
            heardMs += (now - lastTickMs).coerceIn(0L, 5_000L)
            val need = if (durationMs > 0) minOf(durationMs / 2, FOUR_MIN) else FOUR_MIN
            if (heardMs >= need) {
                counted = true
                count(station, streamTitle, now)
            }
        }
        lastTickMs = now
    }

    /**
     * What the rule watches: the track URL for library tracks, the stream
     * title for opted-in radio (a title change is a new song). Null means
     * nothing countable is audible.
     */
    private fun trackKey(station: Station?, streamTitle: String): String? {
        if (station == null) return null
        return when {
            station.source == StationSource.Local || station.source == StationSource.Provider ->
                station.url
            radioOn && station.isRadio && streamTitle.isNotBlank() ->
                "${station.url}\n$streamTitle"
            else -> null
        }
    }

    private fun count(station: Station, streamTitle: String, nowMs: Long) {
        scope.launch {
            if (station.source == StationSource.Local || station.source == StationSource.Provider) {
                runCatching { dao.record(station.url, nowMs) }
            }
            val (artist, title, album) = payloadOf(station, streamTitle)
            outbox.enqueue(
                ScrobbleEntity(
                    url = station.url,
                    artist = artist,
                    title = title,
                    album = album,
                    listenedAtSec = nowMs / 1000L,
                    createdAt = nowMs,
                )
            )
            drain()
        }
    }

    private fun payloadOf(station: Station, streamTitle: String): Triple<String, String, String> =
        when {
            station.source == StationSource.Local || station.source == StationSource.Provider ->
                Triple(
                    station.artist.ifBlank { "unknown artist" },
                    station.name.ifBlank { "untitled" },
                    station.album,
                )
            else -> {
                val (artist, title) = splitStreamTitle(streamTitle)
                Triple(
                    artist.ifBlank { "unknown artist" },
                    title.ifBlank { "untitled" },
                    "",
                )
            }
        }

    /** Resend everything due, with backoff; one drain at a time. */
    fun requestDrain() {
        scope.launch { drain() }
    }

    private suspend fun drain() {
        drainMutex.withLock {
            val now = System.currentTimeMillis()
            runCatching { outbox.prune(now - MAX_ROW_AGE_MS) }
            val token = runCatching { prefs.listenBrainzTokenSync() }.getOrNull().orEmpty()
            if (token.isBlank()) return
            for (row in runCatching { outbox.due(now) }.getOrNull().orEmpty()) {
                val failure = runCatching {
                    postListen(token, row.artist, row.title, row.album, row.listenedAtSec)
                }.exceptionOrNull()
                if (failure == null) {
                    runCatching { outbox.remove(row.id) }
                    _lastError.value = null
                } else {
                    val attempts = row.attempts + 1
                    runCatching { outbox.defer(row.id, attempts, now + retryDelayMs(row.attempts)) }
                    _lastError.value = failure.message?.take(120) ?: "could not reach listenbrainz"
                }
            }
        }
    }

    private suspend fun postListen(
        token: String,
        artist: String,
        title: String,
        album: String,
        listenedAt: Long,
    ) {
        Http.postJson(
            "https://api.listenbrainz.org/1/submit-listens",
            listenBody(artist, title, album, listenedAt),
            mapOf("Authorization" to "Token $token"),
        )
    }
}

/** Submit body for one listen. Internal for unit tests: the API speaks
 * snake_case, so the field names are the contract (HTTP 400 otherwise). */
internal fun listenBody(artist: String, title: String, album: String, listenedAt: Long): String =
    Http.json.encodeToString(
        ListenPayload.serializer(),
        ListenPayload(
            listenType = "single",
            payload = listOf(
                Listen(
                    listenedAt = listenedAt,
                    trackMetadata = TrackMetadata(
                        artistName = artist,
                        trackName = title,
                        releaseName = album.takeIf { it.isNotBlank() },
                    ),
                ),
            ),
        ),
    )

/** Radio stream titles name `Artist - Title`; anything else is title-only. */
internal fun splitStreamTitle(title: String): Pair<String, String> {
    val i = title.indexOf(" - ")
    return if (i < 0) "" to title.trim()
    else title.substring(0, i).trim() to title.substring(i + 3).trim()
}

/** Resend backoff by attempt: 1m, 5m, 15m, 1h, 6h, then daily. */
internal fun retryDelayMs(attempts: Int): Long {
    val steps = longArrayOf(60_000L, 300_000L, 900_000L, 3_600_000L, 21_600_000L, 86_400_000L)
    return steps[attempts.coerceIn(0, steps.lastIndex)]
}

/** True for live radio (everything that is not a finite track). */
private val Station.isRadio: Boolean
    get() = source != StationSource.Local && source != StationSource.Provider &&
        source != StationSource.Podcast

@kotlinx.serialization.Serializable
private data class ListenPayload(
    @kotlinx.serialization.SerialName("listen_type") val listenType: String,
    val payload: List<Listen>,
)

@kotlinx.serialization.Serializable
private data class Listen(
    @kotlinx.serialization.SerialName("listened_at") val listenedAt: Long,
    @kotlinx.serialization.SerialName("track_metadata") val trackMetadata: TrackMetadata,
)

@kotlinx.serialization.Serializable
private data class TrackMetadata(
    @kotlinx.serialization.SerialName("artist_name") val artistName: String,
    @kotlinx.serialization.SerialName("track_name") val trackName: String,
    @kotlinx.serialization.SerialName("release_name") val releaseName: String? = null,
)

@kotlinx.serialization.Serializable
private data class TokenCheck(
    val valid: Boolean = false,
    @kotlinx.serialization.SerialName("user_name") val userName: String? = null,
)

/**
 * The wizard's TEST: asks ListenBrainz whether [token] is theirs, returning
 * the username on success. Nothing is saved until this succeeds, the same
 * rule every provider probe follows.
 */
// The dropped message embeds the request URL including the token, which must
// never reach logs; "token rejected" is the whole public signal.
@Suppress("SwallowedException")
suspend fun validateListenBrainzToken(token: String): Result<String> = runCatching {
    val raw = try {
        Http.text(
            "https://api.listenbrainz.org/1/validate-token?token=$token",
            emptyMap(),
        )
    } catch (e: IllegalStateException) {
        // Http.text raises non-2xx as "HTTP ...": a dead token, while a dead
        // network arrives as an IOException and keeps its own message.
        error("token rejected")
    }
    val check = Http.json.decodeFromString(TokenCheck.serializer(), raw)
    check(check.valid) { "token rejected" }
    check.userName?.takeIf { it.isNotBlank() } ?: "listenbrainz"
}
