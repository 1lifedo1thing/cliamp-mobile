package stream.kleeamp.mobile.podcasts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.common.stateInUi
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.Request
import stream.kleeamp.mobile.net.Http
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.prefs.Prefs

    /** Parallel fetch choices offered in settings. */
const val DEFAULT_PARALLEL_DOWNLOADS = 3
val PARALLEL_DOWNLOAD_CHOICES = listOf(1, 3, 5)

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
    /** Fetched by auto-download rather than a tap; retention only sweeps these. */
    val auto: Boolean = false,
)

/** Transient per-URL fetch state; anything permanent lives in [DownloadEntry]. */
sealed interface DownloadState {
    data object Idle : DownloadState
    /** Waiting for a fetch slot; cancellable while parked. */
    data object Queued : DownloadState
    data class Active(val fraction: Float, val bytesRead: Long, val totalBytes: Long) : DownloadState {
        /** Negative while the server hides its length; the row then reads bytes. */
        val indeterminate: Boolean get() = fraction < 0f
    }
    /** Parked by the user: the partial file stays and resume continues it. */
    data class Paused(val bytesRead: Long, val totalBytes: Long) : DownloadState
    data class Failed(val reason: String) : DownloadState
}

/** Which played downloads are deleted after listening. */
enum class CleanupScope(val key: String) {
    Off("off"),
    Auto("auto"),
    All("all");

    /** Manual downloads survive unless the scope covers everything. */
    fun covers(auto: Boolean): Boolean = when (this) {
        Off -> false
        Auto -> auto
        All -> true
    }

    companion object {
        fun of(key: String): CleanupScope = entries.firstOrNull { it.key == key } ?: Off
    }
}

/** In-flight fetch identity: what to show in the queue and how to retry it. */
internal data class DlMeta(val station: Station, val auto: Boolean)

/** One queue row: an active, queued or failed fetch with its episode. */
data class DownloadQueueItem(val url: String, val station: Station, val state: DownloadState, val auto: Boolean)

/**
 * Queue order, stable: active fetches first, then queued, paused, failures.
 * Pure so the row order is unit-tested on the JVM.
 */
internal fun orderQueueItems(items: List<DownloadQueueItem>): List<DownloadQueueItem> =
    items.sortedWith(
        compareBy(
            { when (it.state) {
                is DownloadState.Active -> 0
                is DownloadState.Queued -> 1
                is DownloadState.Paused -> 2
                else -> 3
            } },
        ),
    )

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
    private val jobsLock = Any()
    /**
     * URLs the user parked: their jobs are cancelled but the partial `.tmp`
     * stays, so resume continues it with a Range request. Guarded by
     * [jobsLock] with [jobs]; the fetch job's cancel/finish paths read it to
     * decide between keeping and deleting the partial file.
     */
    private val paused = mutableSetOf<String>()
    /** Last emitted progress per parked URL, for the Paused row state. */
    private val pausedProgress = mutableMapOf<String, Pair<Long, Long>>()
    /**
     * Strict start order: every queued fetch takes a ticket, and each job
     * reaches the slots only after the previous ticket arrived. Fresh
     * launches hit the dispatcher in whatever order, so without this the
     * first fetches would start randomly; the semaphore grants FIFO, so
     * ordered arrivals mean ordered starts. All guarded by [jobsLock].
     */
    private var nextTicket = 0L
    private var lastArrival: CompletableDeferred<Unit>? = null
    private val arrivals = mutableMapOf<String, CompletableDeferred<Unit>>()
    /**
     * Fetch slots, sized from the parallel-downloads setting. Recreated when
     * the setting changes; in-flight fetches keep their old slot reference
     * and release it, so a change never strands a permit.
     */
    private var slots = Semaphore(DEFAULT_PARALLEL_DOWNLOADS)
    /** Refuse to start below this free space; episodes are tens of MB. */
    private val minFreeBytes = 100L * 1024 * 1024

    private val _entries = MutableStateFlow<Map<String, DownloadEntry>>(emptyMap())
    val entries: StateFlow<Map<String, DownloadEntry>> = _entries.asStateFlow()

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    private val _meta = MutableStateFlow<Map<String, DlMeta>>(emptyMap())
    internal val meta: StateFlow<Map<String, DlMeta>> = _meta.asStateFlow()

    /**
     * The downloading view's rows: every active, queued, paused or failed
     * fetch with its episode, active first. Completed fetches leave the queue
     * for the downloads list; cancelled ones vanish.
     */
    val queue: StateFlow<List<DownloadQueueItem>> =
        combine(_states, _meta) { states, meta ->
            orderQueueItems(
                states.mapNotNull { (url, st) ->
                    val m = meta[url] ?: return@mapNotNull null
                    if (st is DownloadState.Active || st is DownloadState.Queued ||
                        st is DownloadState.Paused || st is DownloadState.Failed
                    ) {
                        DownloadQueueItem(url, m.station, st, m.auto)
                    } else {
                        null
                    }
                },
            )
        }.stateInUi(scope, emptyList())

    init {
        scope.launch {
            prefs.parallelDownloads.collect { v ->
                val n = if (v == 1 || v == 5) v else DEFAULT_PARALLEL_DOWNLOADS
                synchronized(jobsLock) {
                    // In-flight fetches captured the old instance in a local
                    // and release that one, so swapping here never strands a
                    // permit. A change mid-fetch may briefly overshoot the new
                    // limit; the next fetches settle on it.
                    slots = Semaphore(n)
                }
            }
        }
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
    // Any fetch failure lands in the Failed state by design; the slot,
    // persist and state transitions stay in one state machine on purpose.
    // A fetch started over a paused partial file resumes it: the `.tmp`
    // length becomes a Range request, honoured (206, append) or not (200,
    // restart) depending on the server.
    // [initial] is the optimistic progress a resume shows from the first
    // frame instead of flashing 0%: the byte stream corrects it on emit.
    @Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
    fun download(station: Station, auto: Boolean = false, initial: DownloadState.Active? = null) {
        val url = station.url
        if (isDownloaded(url)) return
        synchronized(jobsLock) {
            if (jobs.containsKey(url)) return
            // A fresh fetch owns the URL again: a retry of a parked one
            // resumes its partial file below instead of idling paused.
            paused.remove(url)
            pausedProgress.remove(url)
            // A stale entry (file gone outside the app) would render the
            // same URL in both the queue and the finished list, and
            // duplicate keys crash the list - so the lie goes before the
            // fetch starts. Success writes a fresh entry anyway.
            if (_entries.value[url]?.let { !File(it.path).exists() } == true) {
                _entries.value = _entries.value - url
                scope.launch { runCatching { prefs.removeDownload(url) } }
            }
            if (!station.isTrack || !(url.startsWith("http://") || url.startsWith("https://"))) {
                setState(url, DownloadState.Failed("not downloadable"))
                return
            }
            if (dir.usableSpace < minFreeBytes) {
                setState(url, DownloadState.Failed("storage full"))
                return
            }
            _meta.value = _meta.value + (url to DlMeta(station, auto))
            // Capture the current slots: the setting may swap the instance
            // mid-fetch, and this job must release the one it acquired.
            val gate = synchronized(jobsLock) { slots }
            // Take the next arrival ticket: this job reaches the slots only
            // after the previous ticket arrived, so starts follow queue
            // order no matter how the dispatcher interleaves launches.
            val prev: CompletableDeferred<Unit>?
            val mine: CompletableDeferred<Unit>
            synchronized(jobsLock) {
                nextTicket++
                prev = lastArrival
                mine = CompletableDeferred<Unit>()
                lastArrival = mine
                arrivals[url] = mine
            }
            jobs[url] = scope.launch(Dispatchers.IO) {
                setState(url, initial ?: DownloadState.Queued)
                try {
                    prev?.await()
                } finally {
                    // Arrival handoff, not completion: the next ticket may
                    // line up while this fetch still waits for a slot.
                    // Idempotent with the cancel/remove path completing a
                    // job that never started.
                    mine.complete(Unit)
                    synchronized(jobsLock) { arrivals.remove(url) }
                }
                gate.acquire()
                try {
                    if (!prefs.cellular.first() && !unmetered()) {
                        setState(url, DownloadState.Failed("wifi only"))
                        return@launch
                    }
                    setState(url, initial ?: DownloadState.Active(0f, 0L, -1L))
                    val tmp = File(dir, fileName(url) + ".tmp")
                    val resumeFrom = tmp.takeIf { it.exists() }?.length() ?: 0L
                    val reqBuilder = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT)
                    if (resumeFrom > 0) reqBuilder.header("Range", "bytes=$resumeFrom-")
                    Http.streamClient.newCall(reqBuilder.build()).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            setState(url, DownloadState.Failed("http ${resp.code}"))
                            return@launch
                        }
                        val resumed = resp.code == 206 && resumeFrom > 0
                        val remaining = resp.body.contentLength()
                        val total = when {
                            resumed && remaining > 0 -> resumeFrom + remaining
                            !resumed -> remaining
                            else -> -1L
                        }
                        if (total > 0 && total > dir.usableSpace) {
                            setState(url, DownloadState.Failed("not enough space"))
                            return@launch
                        }
                        // The server ignored the Range: the partial file is a
                        // prefix of nothing, so restart instead of appending.
                        if (!resumed) runCatching { tmp.delete() }
                        var read = if (resumed) resumeFrom else 0L
                        var lastEmit = 0L
                        resp.body.byteStream().use { input ->
                            FileOutputStream(tmp, resumed).use { output ->
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
                            auto = auto,
                        )
                        prefs.addDownload(entry)
                        _entries.value = _entries.value + (url to entry)
                        clearState(url)
                        dropMeta(url)
                    }
                } catch (e: CancellationException) {
                    // A pause parks the fetch with its partial file; any
                    // other cancellation reads idle again.
                    val parked = synchronized(jobsLock) { url in paused }
                    if (parked) {
                        val (read, total) = synchronized(jobsLock) {
                            pausedProgress[url] ?: (0L to -1L)
                        }
                        setState(url, DownloadState.Paused(read, total))
                    } else {
                        setState(url, DownloadState.Idle)
                    }
                    throw e
                } catch (e: Exception) {
                    setState(url, DownloadState.Failed(e.message?.lowercase()?.take(42) ?: "download failed"))
                } finally {
                    gate.release()
                    val keepPartial = synchronized(jobsLock) {
                        jobs.remove(url)
                        url in paused
                    }
                    if (!keepPartial) runCatching { File(dir, fileName(url) + ".tmp").delete() }
                }
            }
        }
    }

    fun cancel(url: String) {
        val job = synchronized(jobsLock) {
            paused.remove(url)
            pausedProgress.remove(url)
            // A queued fetch cancelled before its first run never reaches
            // its arrival handoff: complete it here or the chain stalls.
            arrivals.remove(url)?.complete(Unit)
            jobs.remove(url)
        }
        job?.cancel()
        runCatching { File(dir, fileName(url) + ".tmp").delete() }
        if (!isDownloaded(url)) setState(url, DownloadState.Idle)
        dropMeta(url)
    }

    /**
     * Park an active or queued fetch: the job stops but the partial file
     * stays, and the row reads paused with its progress. [resume] continues
     * it with a Range request. A no-op unless the URL is fetching.
     */
    fun pause(url: String) {
        val job = synchronized(jobsLock) {
            val running = jobs[url] ?: return
            val cur = _states.value[url]
            val progress = when (cur) {
                is DownloadState.Active -> cur.bytesRead to cur.totalBytes
                // Queued but already holding a partial file from an earlier
                // park: resume offset comes from the file itself.
                else -> {
                    val have = runCatching { File(dir, fileName(url) + ".tmp").length() }.getOrDefault(0L)
                    have to -1L
                }
            }
            paused.add(url)
            pausedProgress[url] = progress
            // Same stall guard as cancel: a queued fetch parked before its
            // first run never reaches its arrival handoff.
            arrivals.remove(url)?.complete(Unit)
            running
        }
        job.cancel()
        // The job's cancellation path publishes the Paused state; set it
        // here too so a queued fetch parked before acquiring a slot reads
        // paused immediately instead of until its (uncancellable) acquire
        // notices. Both write the same value.
        val (read, total) = synchronized(jobsLock) {
            pausedProgress[url] ?: (0L to -1L)
        }
        if (!isDownloaded(url)) setState(url, DownloadState.Paused(read, total))
    }

    /**
     * Continue a parked fetch from its partial file, showing its kept
     * percent from the first frame. Re-queued at the end of the line.
     * A no-op unless the URL is paused and its episode is still known.
     */
    fun resume(url: String) {
        val station: Station
        val auto: Boolean
        val read: Long
        val total: Long
        synchronized(jobsLock) {
            if (url !in paused) return
            val m = _meta.value[url] ?: run {
                paused.remove(url)
                pausedProgress.remove(url)
                return
            }
            station = m.station
            auto = m.auto
            val progress = pausedProgress[url]
                ?: (_states.value[url] as? DownloadState.Paused)?.let { it.bytesRead to it.totalBytes }
                ?: (0L to -1L)
            read = progress.first
            total = progress.second
        }
        val f = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else -1f
        download(station, auto, DownloadState.Active(f, read, total))
    }

    /**
     * Queue every full episode of [show]: the whole show offline in one tap.
     * Manual fetches, so each keeps its own cellular gate in [download].
     */
    fun downloadAll(show: PodcastShow, episodes: List<PodcastEpisode>) {
        episodes
            .filter { it.isFull && it.audioUrl.isNotBlank() }
            .forEach { download(it.toStation(show)) }
    }

    /**
     * Stop everything and clear the queue: active fetches stop, queued ones
     * never start, parked ones are dropped with their partial files, and
     * failures leave the view. Completed downloads stay.
     */
    fun cancelAll() {
        val urls = synchronized(jobsLock) { jobs.keys.toList() + paused.toList() }
        urls.forEach { cancel(it) }
        _states.value.keys.filter { !isDownloaded(it) }.forEach {
            clearState(it)
            dropMeta(it)
        }
    }

    /**
     * Forget everything: stops the queue and deletes all fetched files.
     * Backs the Downloads page trash key. Optimistic: the snapshot detaches
     * and the lists empty on this frame, while the joins, file deletes and
     * persist catch up in the background.
     */
    fun removeAll() {
        // Jobs, finished files and bare states (failures with no file):
        // every URL the queue or the list could be holding.
        val snapshot: Map<String, String?>
        val jobsToStop: List<Job>
        synchronized(jobsLock) {
            val urls = (
                jobs.keys + _entries.value.keys + _states.value.keys
                ).toSet()
            snapshot = urls.associateWith { _entries.value[it]?.path }
            jobsToStop = jobs.values.toList()
            jobs.clear()
            paused.clear()
            pausedProgress.clear()
            arrivals.values.forEach { it.complete(Unit) }
            arrivals.clear()
        }
        jobsToStop.forEach { it.cancel() }
        _entries.value = _entries.value - snapshot.keys
        snapshot.keys.forEach { clearState(it); dropMeta(it) }
        scope.launch {
            // Join first: a delete landing mid-rename must never remove
            // the file the writer just finished.
            jobsToStop.forEach { runCatching { withTimeoutOrNull(5_000) { it.join() } } }
            snapshot.forEach { (url, path) ->
                // Skip URLs refetched since: their jobs clean up after
                // themselves, and this must never eat a fresh file.
                val busy = synchronized(jobsLock) { jobs.containsKey(url) } || _entries.value.containsKey(url)
                if (!busy && path != null) runCatching { File(path).delete() }
            }
            prefs.setDownloads(_entries.value)
        }
    }

    /**
     * Forget a fetch: stops it, deletes the file, untracks the URL. The
     * writer is joined before the delete so a remove landing mid-rename can
     * never delete the file the writer just finished - cancel alone is async
     * and the rename would win the race.
     */
    fun remove(url: String) {
        scope.launch { removeNow(url) }
    }

    private suspend fun removeNow(url: String) {
        val job = synchronized(jobsLock) {
            paused.remove(url)
            pausedProgress.remove(url)
            arrivals.remove(url)?.complete(Unit)
            jobs.remove(url)
        }
        job?.cancel()
        runCatching { withTimeoutOrNull(5_000) { job?.join() } }
        _entries.value[url]?.let { runCatching { File(it.path).delete() } }
        _entries.value = _entries.value - url
        clearState(url)
        dropMeta(url)
        prefs.removeDownload(url)
    }

    /**
     * Auto-download for one subscribed show: every full, unplayed episode
     * fetches itself. Nothing is ever swept: offline episodes stay until the
     * user removes them or the delete-after-listening scope covers them.
     * Idempotent, so a feed refresh re-firing it is free.
     *
     * Auto fetches are wifi-only unless the cellular opt-in is on; manual
     * taps keep their own [Prefs.cellular] gate inside [download].
     */
    fun autoDownload(
        show: PodcastShow,
        episodes: List<PodcastEpisode>,
        completedUrls: Set<String>,
    ) {
        scope.launch {
            if (!prefs.autoDownload.first()) return@launch
            if (!prefs.autoCellular.first() && !unmetered()) return@launch
            episodes
                .filter { it.isFull && it.audioUrl.isNotBlank() && it.audioUrl !in completedUrls }
                .forEach { ep ->
                    if (!isDownloaded(ep.audioUrl) && !jobs.containsKey(ep.audioUrl)) {
                        download(ep.toStation(show), auto = true)
                    }
                }
        }
    }

    private fun setState(url: String, s: DownloadState) {
        _states.value = _states.value + (url to s)
    }

    private fun clearState(url: String) {
        _states.value = _states.value - url
    }

    private fun dropMeta(url: String) {
        _meta.value = _meta.value - url
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
