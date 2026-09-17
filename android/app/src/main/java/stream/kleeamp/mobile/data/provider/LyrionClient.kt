package stream.kleeamp.mobile.data.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import stream.kleeamp.mobile.net.Http
import stream.kleeamp.mobile.playback.ResolvedStream

/**
 * Lyrion (the Logitech Media Server / Squeezebox) exposes a JSON-RPC API over
 * optional HTTP Basic auth. Its response envelopes are famously inconsistent
 * across servers, so the parser here is deliberately lenient: it hunts for the
 * array of item objects inside the result.
 *
 * Streams are `/music/<id>/download` with the Basic-auth userinfo baked into the
 * URL, so (like Plex and ABS) playback needs no custom headers.
 */
class LyrionClient(
    rawUrl: String,
    private val user: String,
    private val password: String,
) {
    private val base = SubsonicClient.normalise(rawUrl)
    private val authHeader: String
        get() = if (user.isBlank()) "" else "Basic " + android.util.Base64.encodeToString(
            "$user:$password".toByteArray(), android.util.Base64.NO_WRAP,
        )

    private fun headers(): Map<String, String> =
        if (authHeader.isBlank()) emptyMap() else mapOf("Authorization" to authHeader)

    suspend fun ping(): Result<ProviderIdentity> = withContext(Dispatchers.IO) {
        runCatching {
            val r = rpc(listOf("version", "?"))
            ProviderIdentity(
                name = "lyrion · " + SubsonicClient.shortHost(base),
                detail = r.string().takeIf { it.isNotBlank() }.orEmpty(),
            )
        }
    }

    suspend fun albums(style: String): Result<List<ProviderAlbum>> = withContext(Dispatchers.IO) {
        runCatching {
            val r = rpc(listOf("albums", "0", "200", "sort:album", "tags:adly"))
            itemArray(r).map { e ->
                ProviderAlbum(
                    id = field(e, "id") ?: field(e, "album_id").orEmpty(),
                    name = (field(e, "title") ?: field(e, "album")).orEmpty(),
                    artist = (field(e, "artist") ?: field(e, "artist_id")).orEmpty(),
                    songCount = 0,
                    year = field(e, "year")?.toIntOrNull() ?: 0,
                )
            }.filter { it.id.isNotBlank() }
        }
    }

    suspend fun artists(): Result<List<ProviderArtist>> = withContext(Dispatchers.IO) {
        runCatching {
            val r = rpc(listOf("artists", "0", "200", "tags:u"))
            itemArray(r).map { e ->
                ProviderArtist(
                    id = field(e, "id") ?: field(e, "artist_id").orEmpty(),
                    name = field(e, "name") ?: field(e, "artist").orEmpty(),
                    albumCount = 0,
                )
            }.filter { it.id.isNotBlank() }
        }
    }

    suspend fun artistAlbums(artistId: String): Result<List<ProviderAlbum>> = withContext(Dispatchers.IO) {
        runCatching {
            val r = rpc(listOf("albums", "0", "200", "artist_id:$artistId", "tags:adly"))
            itemArray(r).map { e ->
                ProviderAlbum(
                    id = field(e, "id") ?: field(e, "album_id").orEmpty(),
                    name = (field(e, "title") ?: field(e, "album")).orEmpty(),
                    artist = (field(e, "artist") ?: field(e, "artist_id")).orEmpty(),
                    songCount = 0,
                    year = field(e, "year")?.toIntOrNull() ?: 0,
                )
            }.filter { it.id.isNotBlank() }
        }
    }

    suspend fun albumTracks(albumId: String): Result<List<ProviderTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val r = rpc(listOf("titles", "0", "400", "album_id:$albumId", "sort:albumtrack", "tags:adtygu"))
            itemArray(r).map { e ->
                ProviderTrack(
                    id = field(e, "id") ?: field(e, "track_id").orEmpty(),
                    title = (field(e, "title") ?: field(e, "name")).orEmpty(),
                    artist = field(e, "artist").orEmpty(),
                    album = field(e, "album").orEmpty(),
                    duration = field(e, "duration")?.toIntOrNull() ?: 0,
                    codec = "",
                    bitrate = 0,
                )
            }.filter { it.id.isNotBlank() }
        }
    }

    fun stream(trackId: String): ResolvedStream {
        // Baking userinfo into the URL authority means the default data source
        // sends the Basic header, so no custom playback headers are required.
        // Userinfo is percent-encoded: a password containing @ : / or % would
        // otherwise split the URL or leak into the path.
        val url = if (user.isBlank()) "$base/music/$trackId/download" else {
            val scheme = base.substringBefore("://") + "://"
            val rest = base.removePrefix(scheme)
            "$scheme${userinfo(user)}:${userinfo(password)}@$rest/music/$trackId/download"
        }
        return ResolvedStream(url)
    }

    fun coverUrl(albumId: String): String = ""

    private fun userinfo(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Core jsonrpc call; returns the `result` element (often an array). */
    private suspend fun rpc(args: List<String>): JsonElement = withContext(Dispatchers.IO) {
        val json = kotlinx.serialization.json.buildJsonObject {
            put("method", JsonPrimitive("slim.request"))
            put("params", kotlinx.serialization.json.buildJsonArray {
                add(JsonPrimitive(""))
                add(kotlinx.serialization.json.buildJsonArray {
                    args.forEach { add(JsonPrimitive(it)) }
                })
            })
        }
        val body = Http.postJson("$base/jsonrpc.js", json.toString(), headers())
        Http.json.parseToJsonElement(body).jsonObject["result"] ?: JsonNull
    }

    /**
     * Finds the largest nested array of objects inside [result]. The envelope
     * itself is an object too, so callers drop blank ids - it has none, and
     * querying tracks for a blank album id returns the whole library.
     */
    private fun itemArray(result: JsonElement): List<JsonElement> {
        // Collect all object elements from arrays in traversal order (dedup by identity).
        val seen = LinkedHashSet<JsonElement>()
        fun collect(e: JsonElement) {
            when (e) {
                is JsonArray -> e.forEach { collect(it) }
                is JsonObject -> { seen.add(e); e.values.forEach { collect(it) } }
                else -> {}
            }
        }
        collect(result)
        return seen.filterIsInstance<JsonObject>().toList()
    }

    private fun field(obj: JsonElement, key: String): String? {
        val o = obj as? JsonObject ?: return null
        return (o[key] as? JsonPrimitive)?.contentOrNull
    }

    /** Lyrion `version ?` returns the version string somewhere in the result. */
    private fun JsonElement.string(): String {
        var s = ""
        var best = 0
        fun walk(e: JsonElement, depth: Int) {
            when (e) {
                is JsonPrimitive -> {
                    val v = e.contentOrNull ?: return
                    if (v.isNotBlank() && (v[0].isDigit() || v.startsWith("version"))) {
                        if (depth >= best) { best = depth; s = v }
                    }
                }
                is JsonArray -> e.forEach { walk(it, depth + 1) }
                is JsonObject -> e.values.forEach { walk(it, depth + 1) }
            }
        }
        walk(this, 0)
        return s
    }
}