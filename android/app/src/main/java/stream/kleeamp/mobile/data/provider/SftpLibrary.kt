package stream.kleeamp.mobile.data.provider

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import stream.kleeamp.mobile.data.db.KleeampDatabase
import stream.kleeamp.mobile.data.db.SftpDao
import stream.kleeamp.mobile.data.db.SftpIndexEntity
import stream.kleeamp.mobile.data.db.SftpTrackEntity

private const val TAG = "kleeamp/sftp"

/**
 * The scanned contents of every SSH account, and the scans that fill them.
 *
 * A singleton for the same reason [stream.kleeamp.mobile.data.StationArtSource]
 * is one: the data source that feeds the player runs on ExoPlayer's loader
 * thread with nothing but an account id and a path, and threading a context and
 * a repository down to it would mean plumbing through Media3.
 */
object SftpLibrary {

    private lateinit var appContext: Context
    private lateinit var store: ProviderStore
    private lateinit var scope: CoroutineScope

    private val dao: SftpDao by lazy { KleeampDatabase.get(appContext).sftp() }
    private val scanning = HashSet<String>()
    private val statuses = HashMap<String, MutableStateFlow<IndexState>>()

    /**
     * Accounts, cached so that [account] can answer without suspending. The
     * data source needs an answer on a thread that cannot wait on a Flow.
     */
    @Volatile private var snapshot: List<ProviderAccount> = emptyList()

    fun init(context: Context, providers: ProviderStore, appScope: CoroutineScope) {
        appContext = context.applicationContext
        store = providers
        scope = appScope
        appScope.launch { providers.accounts.collect { snapshot = it } }
    }

    /**
     * Blocking lookup by id. Falls back to the database because playback can
     * start from the restored last station before the accounts flow has had a
     * chance to emit anything.
     */
    fun account(id: String): ProviderAccount? {
        snapshot.firstOrNull { it.id == id }?.let { return it }
        return runCatching { runBlocking { store.read() } }
            .getOrDefault(emptyList())
            .firstOrNull { it.id == id }
            ?.also { snapshot = snapshot + it }
    }

    fun status(accountId: String): StateFlow<IndexState> = statusFlow(accountId)

    @Synchronized
    private fun statusFlow(accountId: String): MutableStateFlow<IndexState> =
        statuses.getOrPut(accountId) { MutableStateFlow(IndexState()) }

    /**
     * Claims the right to scan an account, or reports that someone already has
     * it. The browse screen asks on every root it shows and on every reload,
     * which without this would queue a fresh walk behind each one.
     */
    @Synchronized
    private fun claimScan(accountId: String): Boolean = scanning.add(accountId)

    @Synchronized
    private fun releaseScan(accountId: String) { scanning.remove(accountId) }

    /**
     * Starts a scan if the account has never been indexed or its folders have
     * changed since it was, and returns without waiting for it.
     *
     * Waiting would mean the browse screen sits on "loading" for as long as a
     * walk of a large tree takes. Instead the screen renders whatever is
     * already indexed, shows what the scan is doing, and reloads when it lands.
     *
     * Readers that need data (not a screen) use [awaitIndexed]: the scan flag
     * is marked synchronously here, so an awaiter can never observe a stale
     * idle between the claim and the launched scan's first line.
     */
    suspend fun ensureIndexed(account: ProviderAccount) {
        val cfg = account.ssh()
        if (cfg.folders.isEmpty()) return
        val recorded = runCatching { dao.index(account.id) }.getOrNull()
        val stale = recorded == null || recorded.folders != cfg.folders.joinToString("\n")
        if (!stale) return
        if (!claimScan(account.id)) return
        statusFlow(account.id).value = IndexState(scanning = true, text = "scanning…")
        scope.launch {
            try {
                scanInner(account)
            } finally {
                releaseScan(account.id)
            }
        }
    }

    /**
     * Trigger a stale index and suspend until it lands, so the first open of
     * a fresh account reads music instead of an empty database that nothing
     * ever re-reads. Fresh indexes return at once; a running scan is joined;
     * the timeout yields whatever is indexed so far instead of hanging a
     * list that only ever grows by re-entry.
     */
    suspend fun awaitIndexed(account: ProviderAccount, timeoutMs: Long = 180_000L): Boolean {
        ensureIndexed(account)
        val status = statusFlow(account.id)
        if (!status.value.scanning) return true
        return withTimeoutOrNull(timeoutMs) { status.first { !it.scanning } } != null
    }

    /** An explicit rescan, awaited so the caller can report what happened. */
    suspend fun rescan(account: ProviderAccount): Result<Unit> = scan(account)

    /** Drops an account's index; called when the account itself goes away. */
    suspend fun forget(accountId: String) {
        runCatching {
            dao.clear(accountId)
            dao.clearIndex(accountId)
        }
        statusFlow(accountId).value = IndexState()
    }

    private suspend fun scan(account: ProviderAccount): Result<Unit> {
        val cfg = account.ssh()
        if (cfg.folders.isEmpty()) {
            return Result.failure(IllegalStateException("no folders configured"))
        }
        if (!claimScan(account.id)) return Result.success(Unit)
        return try {
            scanInner(account)
        } finally {
            releaseScan(account.id)
        }
    }

    /** The walk itself; claiming and releasing stay with the callers above. */
    private suspend fun scanInner(account: ProviderAccount): Result<Unit> {
        val cfg = account.ssh()
        val status = statusFlow(account.id)
        status.value = IndexState(scanning = true, text = "scanning…")
        return withContext(Dispatchers.IO) {
                runCatching {
                    val scanId = System.currentTimeMillis()
                    var written = 0
                    var lastReport = 0L
                    SshPool.useSftp(account) { sftp ->
                        SftpScan(
                            folders = cfg.folders,
                            onBatch = { batch ->
                                dao.insert(batch.map { it.toEntity(account.id, scanId) })
                                written += batch.size
                            },
                            onProgress = { found, where ->
                                // Throttled: a walk visits directories far
                                // faster than a screen can usefully repaint.
                                val now = System.currentTimeMillis()
                                if (now - lastReport > 400) {
                                    lastReport = now
                                    status.value = IndexState(
                                        scanning = true,
                                        text = "scanning · $found tracks · ${where.substringAfterLast('/')}",
                                    )
                                }
                            },
                        ).run(sftp)
                    }
                    // Anything still carrying an older scan id was not found
                    // this time round, so it is gone from the server.
                    dao.pruneOlderThan(account.id, scanId)
                    dao.record(
                        SftpIndexEntity(
                            accountId = account.id,
                            folders = cfg.folders.joinToString("\n"),
                            scannedAt = scanId,
                            tracks = written,
                        )
                    )
                    written
                }
            }.fold(
                onSuccess = { count ->
                    status.value = IndexState(scanning = false, text = "$count tracks indexed")
                    Result.success(Unit)
                },
                onFailure = { failure ->
                    Log.w(TAG, "scan of ${account.label} failed: ${failure.message}")
                    status.value = IndexState(
                        scanning = false,
                        text = failure.message ?: "scan failed",
                    )
                    Result.failure(failure)
                },
            )
    }

    internal suspend fun albums(accountId: String, style: String): List<SftpTrackAlbum> = when (style) {
        "az" -> dao.albumsByName(accountId)
        else -> dao.albumsByNewest(accountId)
    }.map { SftpTrackAlbum(it.id, it.name, it.artist, it.songCount, it.year) }

    internal suspend fun artistAlbums(accountId: String, artistKey: String): List<SftpTrackAlbum> =
        dao.albumsByArtist(accountId, artistKey)
            .map { SftpTrackAlbum(it.id, it.name, it.artist, it.songCount, it.year) }

    internal suspend fun artists(accountId: String) = dao.artists(accountId)

    internal suspend fun albumTracks(accountId: String, albumKey: String) =
        dao.albumTracks(accountId, albumKey)
}

/** The shape [SftpLibrary] hands back, before it becomes a [ProviderAlbum]. */
internal data class SftpTrackAlbum(
    val id: String,
    val name: String,
    val artist: String,
    val songCount: Int,
    val year: Int,
)

private fun ScannedTrack.toEntity(accountId: String, scanId: Long) = SftpTrackEntity(
    accountId = accountId,
    path = path,
    title = title,
    artist = artist,
    album = album,
    albumKey = albumKey,
    artistKey = artistKey,
    track = track,
    year = year,
    size = size,
    mtime = mtime,
    ext = ext,
    scanId = scanId,
)
