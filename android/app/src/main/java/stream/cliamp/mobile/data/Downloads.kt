package stream.cliamp.mobile.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.Request
import stream.cliamp.mobile.net.Http

/**
 * One fetched file: where it lives plus the station snapshot that plays it.
 * Keyed by the remote audio URL everywhere, the same key progress and history
 * already use, so a download, a resume position and a queue item always agree
 * on what "this episode" is.
 */
@Serializable
data class DownloadEntry(
    val url: String,
    val path: String,
    val bytes: Long = 0L,
    val station: Station,
    val downloadedAt: Long = 0L,
)

/** Transient per-URL fetch state; anything permanent lives in [DownloadEntry]. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Active(val fraction: Float, val bytesRead: Long, val totalBytes: Long) : DownloadState {
        /** Negative while the server hides its length; the row then reads bytes. */
        val indeterminate: Boolean get() = fraction < 0f
    }
    data class Failed(val reason: String) : DownloadState
}

/** `38 MB` / `410 KB`, for rows that name a file's weight. */
internal fun downloadSizeLabel(bytes: Long): String =
    if (bytes < 1024 * 1024) "${(bytes / 1024).coerceAtLeast(1)} KB"
    else "${"%.0f".format(bytes / 1048576f)} MB"

/**
 * Episode fetcher. No WorkManager on purpose: the set is small, the files are
 * plain HTTP bodies, and an app-scoped coroutine with an atomic rename is the
 * whole manager. Fetches run while the app lives; a kill leaves a `.tmp`
 * behind, which the next start sweeps and the row simply reads idle again.
 *
 * Only finite http(s) tracks are fetchable - episodes today. Live radio has
 * no end to save, and provider tracks need per-request auth headers the file
 * would then bypass.
 */
class DownloadStore(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val dir = File(context.filesDir, "episode-downloads").apply { mkdirs() }
    private val jobs = mutableMapOf<String, Job>()

    private val _entries = MutableStateFlow<Map<String, DownloadEntry>>(emptyMap())
    val entries: StateFlow<Map<String, DownloadEntry>> = _entries.asStateFlow()

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    init {
        scope.launch {
            dir.listFiles { f -> f.extension == "tmp" }?.forEach { runCatching { it.delete() } }
            val stored = prefs.downloads.first()
            val kept = stored.filterValues { File(it.path).exists() }
            _entries.value = kept
            if (kept.size != stored.size) prefs.setDownloads(kept)
        }
    }

    /** Absolute path when [url] has a live file on disk, else null. */
    fun localPath(url: String): String? =
        _entries.value[url]?.path?.takeIf { File(it).exists() }

    fun isDownloaded(url: String): Boolean = localPath(url) != null

    /** Queue a fetch; a no-op when already held or already running. */
    fun download(station: Station) {
        val url = station.url
        if (isDownloaded(url) || jobs.containsKey(url)) return
        if (!station.isTrack || !(url.startsWith("http://") || url.startsWith("https://"))) {
            setState(url, DownloadState.Failed("not downloadable"))
            return
        }
        jobs[url] = scope.launch(Dispatchers.IO) {
            try {
                if (!prefs.cellular.first() && !unmetered()) {
                    setState(url, DownloadState.Failed("wifi only"))
                    return@launch
                }
                setState(url, DownloadState.Active(0f, 0L, -1L))
                val req = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
                Http.streamClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        setState(url, DownloadState.Failed("http ${resp.code}"))
                        return@launch
                    }
                    val total = resp.body.contentLength()
                    val tmp = File(dir, fileName(url) + ".tmp")
                    var read = 0L
                    var lastEmit = 0L
                    resp.body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                read += n
                                val now = System.currentTimeMillis()
                                if (now - lastEmit > 400 || (total > 0 && read >= total)) {
                                    lastEmit = now
                                    val f = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else -1f
                                    setState(url, DownloadState.Active(f, read, total))
                                }
                            }
                        }
                    }
                    val final = File(dir, fileName(url))
                    runCatching { final.delete() }
                    tmp.renameTo(final)
                    val entry = DownloadEntry(
                        url = url,
                        path = final.absolutePath,
                        bytes = final.length(),
                        station = station,
                        downloadedAt = System.currentTimeMillis(),
                    )
                    prefs.addDownload(entry)
                    _entries.value = _entries.value + (url to entry)
                    clearState(url)
                }
            } catch (e: CancellationException) {
                setState(url, DownloadState.Idle)
                throw e
            } catch (e: Exception) {
                setState(url, DownloadState.Failed(e.message?.lowercase()?.take(42) ?: "download failed"))
            } finally {
                jobs.remove(url)
                runCatching { File(dir, fileName(url) + ".tmp").delete() }
            }
        }
    }

    fun cancel(url: String) {
        jobs.remove(url)?.cancel()
        if (!isDownloaded(url)) setState(url, DownloadState.Idle)
    }

    /** Forget a fetch: stops it, deletes the file, untracks the URL. */
    fun remove(url: String) {
        cancel(url)
        _entries.value[url]?.let { runCatching { File(it.path).delete() } }
        _entries.value = _entries.value - url
        clearState(url)
        scope.launch { prefs.removeDownload(url) }
    }

    private fun setState(url: String, s: DownloadState) {
        _states.value = _states.value + (url to s)
    }

    private fun clearState(url: String) {
        _states.value = _states.value - url
    }

    private fun unmetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun fileName(url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "")
            .takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "mp3"
        val sha = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$sha.$ext"
    }
}
