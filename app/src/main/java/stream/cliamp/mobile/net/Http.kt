package stream.cliamp.mobile.net

import kotlinx.coroutines.Dispatchers
import stream.cliamp.mobile.BuildConfig
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * One shared client. radio-browser.info asks every client to identify itself,
 * so the User-Agent is not optional politeness - it is how they rate-limit.
 */
object Http {
    const val USER_AGENT = "cliamp-mobile/${BuildConfig.VERSION_NAME} (+https://cliamp.stream)"

    @Volatile private var cacheDir: java.io.File? = null

    /** Called once from Application so artwork lookups can be cached on disk. */
    fun init(context: android.content.Context) {
        cacheDir = java.io.File(context.cacheDir, "http")
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply { cacheDir?.let { cache(okhttp3.Cache(it, 48L * 1024 * 1024)) } }
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} for $url")
            r.body.string()
        }
    }

    /** Fire-and-forget; used for the directory's click counter. */
    suspend fun ping(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
