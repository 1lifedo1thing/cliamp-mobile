package stream.cliamp.mobile.data.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import stream.cliamp.mobile.net.Http
import java.security.MessageDigest
import java.security.SecureRandom
import java.net.URLEncoder

/**
 * Navidrome speaks the Subsonic API, so this one client also covers Gonic,
 * Airsonic and anything else implementing it.
 *
 * There is no session to establish. Every request carries a fresh salt and
 * `md5(password + salt)`, which is what the Subsonic spec mandates. MD5 here is
 * the protocol, not a security choice on our part, and the consequence worth
 * knowing is that the password itself has to be retrievable to sign each
 * request. It cannot be traded for a token once and forgotten, which is why it
 * goes through [SecretStore] rather than into plain preferences.
 */
class SubsonicClient(
    rawUrl: String,
    private val user: String,
    private val password: String,
) {
    private val base = normalise(rawUrl)

    suspend fun ping(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = Http.text(endpoint("ping.view"))
            val res = Http.json.decodeFromString<SubsonicEnvelope>(body).response
            if (!res.isOk) error(res.error?.message ?: "server refused the credentials")
            listOfNotNull(
                res.type?.takeIf { it.isNotBlank() },
                res.serverVersion?.takeIf { it.isNotBlank() },
            ).joinToString(" ").ifBlank { "subsonic ${res.version.orEmpty()}".trim() }
        }
    }

    fun streamUrl(id: String): String = endpoint("stream.view", mapOf("id" to id))

    private fun endpoint(view: String, extra: Map<String, String> = emptyMap()): String {
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }.toHex()
        val token = MessageDigest.getInstance("MD5")
            .digest((password + salt).toByteArray())
            .toHex()
        val params = buildMap {
            put("u", user)
            put("t", token)
            put("s", salt)
            put("v", API_VERSION)
            put("c", CLIENT)
            put("f", "json")
            putAll(extra)
        }
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        }
        return "$base/rest/$view?$query"
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        private const val API_VERSION = "1.16.1"
        private const val CLIENT = "cliamp"

        /** People paste "music.example.com/" as often as a real URL. */
        fun normalise(raw: String): String {
            val t = raw.trim().trimEnd('/')
            if (t.isEmpty()) return t
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }
    }
}

@Serializable
private data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse(),
)

@Serializable
private data class SubsonicResponse(
    val status: String = "",
    val version: String? = null,
    val type: String? = null,
    val serverVersion: String? = null,
    val error: SubsonicError? = null,
) {
    val isOk: Boolean get() = status.equals("ok", ignoreCase = true)
}

@Serializable
private data class SubsonicError(val code: Int = 0, val message: String = "")
