package stream.kleeamp.mobile.library

import android.Manifest
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.db.KleeampDatabase
import stream.kleeamp.mobile.db.LocalSongEntity
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import java.io.File

/**
 * A song picked out of the device. The [station] form is what the rest of the
 * app plays: a local file is just a Station whose `url` is a file/content URI,
 * so the whole playback pipeline behaves exactly as it does for a stream.
 */
data class LocalSong(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    /** duration in milliseconds. */
    val durationMs: Long,
    /** When the file first entered the media store, seconds since epoch. */
    val dateAdded: Long,
    val uri: Uri,
    val cover: String,
) {
    val station: Station
        get() = Station(
            id = "local:$path",
            name = title,
            url = uri.toString(),
            source = StationSource.Local,
            cover = cover,
            artist = artist,
            album = album,
            durationMs = durationMs,
            dateAdded = dateAdded,
        )

    /** Longest sort field is the safe proxy for "starts with title". */
    val sortKey: String get() = title.lowercase()
}

/** "4:32" style duration for local-song rows and headers. */
fun durationLabel(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

/**
 * MediaStore album-art uri for an album, or null when the row names no album.
 * Pure string math, so it stays unit-testable without the framework: the OS
 * serves `content://media/external/audio/albumart/{id}` and rows read it
 * through the cover pipeline like any other cover string.
 */
internal fun albumArtUri(albumId: Long): String? =
    if (albumId > 0) "content://media/external/audio/albumart/$albumId" else null

/**
 * The local library: every audio file on the device. This is enumerated via
 * MediaStore, exactly the way Samsung Music and friends do it — a fast,
 * OS-maintained index of every track in internal storage, an SD card, folders
 * like Download/Melodify, and anywhere else — so nothing is "missed" just
 * because it doesn't live in a Music folder.
 *
 * The result is cached to disk: the first ever load queries MediaStore, but
 * every later one reads the cached snapshot instantly and refreshes in the
 * background, so opening the Library is never slow again.
 */
class LocalLibrary(context: Context, private val scope: CoroutineScope) {

    private val resolver = context.contentResolver
    private val dao = KleeampDatabase.get(context).localSongs()

    /** The queried collection: the external volume on Q+, the legacy uri below. */
    private val collection: Uri = if (Build.VERSION.SDK_INT >= 29) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    private val _songs = MutableStateFlow<List<Station>>(emptyList())
    val songs: StateFlow<List<Station>> = _songs.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val scanLock = Any()
    private var scanJob: Job? = null
    private var watching = false
    private var watchDebounce: Job? = null

    fun refresh() {
        // The observer starts here, so both the launch path (KleeampApp) and
        // the grant path (Library screen) watch without a rescan button.
        watch()
        // Serve whatever we already have now. On a warm launch that is the disk
        // cache, so the list paints instantly and never flashes a scan message;
        // on a cold install the cache is empty and the UI shows "scanning…"
        // until the MediaStore query below fills it.
        if (_songs.value.isEmpty()) {
            _loading.value = true
        }
        _error.value = null
        // One scan at a time: startup and the Library visit both fire this,
        // and the second call would otherwise re-query MediaStore redundantly.
        synchronized(scanLock) {
            if (scanJob?.isActive == true) return
            scanJob = scope.launch(Dispatchers.IO) { scan() }
        }
    }

    /**
     * Live updates without a rescan button: a ContentObserver on the queried
     * collection refreshes (debounced) when tracks are copied in or deleted,
     * e.g. USB copies and Downloads. Registered once; the observer lives as
     * long as this process-wide singleton.
     */
    private fun watch() {
        synchronized(scanLock) {
            if (watching) return
            watching = true
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                watchDebounce?.cancel()
                watchDebounce = scope.launch {
                    delay(400)
                    refresh()
                }
            }
        }
        runCatching { resolver.registerContentObserver(collection, true, observer) }
            .onFailure { synchronized(scanLock) { watching = false } }
    }

    private suspend fun scan() {
        // Warm launch: Room rows paint first, the cursor refreshes behind
        // them. Cold launch: the spinner runs only until the cursor closes.
        val cached = readCache()
        if (_songs.value.isEmpty() && !cached.isNullOrEmpty()) {
            _songs.value = cached
            _loading.value = false
        } else if (_songs.value.isEmpty()) {
            _loading.value = true
        }

        val tQuery = System.currentTimeMillis()
        val found = runCatching { querySongs() }.getOrElse { e ->
            if (_songs.value.isEmpty()) _error.value = e.message ?: "could not read the library"
            _songs.value
        }
        android.util.Log.d(
            "kleeamp/library",
            "query ${found.size} songs in ${System.currentTimeMillis() - tQuery}ms",
        )

        // The list (and the spinner going away) always wins over the cache
        // write: rows are up the moment the cursor closes, however slow the
        // write below is. An empty cursor clears a stale list the same way —
        // the cursor is the prune, never a File.exists sweep.
        _songs.value = found
        _loading.value = false

        if (found != cached.orEmpty()) {
            val tCache = System.currentTimeMillis()
            writeCache(found)
            android.util.Log.d(
                "kleeamp/library",
                "cache write ${found.size} songs in ${System.currentTimeMillis() - tCache}ms",
            )
        }
    }

    /**
     * Cursor columns only: no File(), no listFiles(), no exists(), no tag
     * reads, no embedded-art extract. The OS already sorted TITLE for us, so
     * Kotlin never sorts again. Covers are album-art uri strings here;
     * bitmaps decode later, when a row binds.
     */
    private fun querySongs(): List<Station> {
        val out = ArrayList<LocalSong>(256)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATA,
        )
        resolver.query(
            collection,
            projection,
            MediaStore.Audio.Media.IS_MUSIC + " != 0",
            null,
            MediaStore.Audio.Media.TITLE + " COLLATE NOCASE",
        )?.use { c ->
            val cols = SongColumns(
                id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID),
                title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE),
                artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST),
                album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM),
                albumId = runCatching { c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID) }.getOrDefault(-1),
                dur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION),
                added = runCatching { c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED) }.getOrDefault(-1),
                // DATA is deprecated but still populated on current devices.
                // Blank rows stay in the list on a content:// item uri; only
                // the id/path form changes, never membership.
                data = runCatching { c.getColumnIndex(MediaStore.Audio.Media.DATA) }.getOrNull() ?: -1,
            )
            while (c.moveToNext()) {
                out += mapRow(c, cols)
            }
        }
        return out.map { it.station }
    }

    /** MediaStore column indices for one scan. */
    private data class SongColumns(
        val id: Int,
        val title: Int,
        val artist: Int,
        val album: Int,
        val albumId: Int,
        val dur: Int,
        val added: Int,
        val data: Int,
    )

    /** One cursor row as a song, strings only. Playlist ids stay local:$path. */
    private fun mapRow(c: Cursor, cols: SongColumns): LocalSong {
        val rowId = c.getLong(cols.id)
        val data = if (cols.data >= 0) c.getString(cols.data) else null
        val path = data?.takeIf { it.isNotBlank() } ?: "media:$rowId"
        val uri = if (!data.isNullOrBlank()) {
            // Uri building only, no disk I/O: keeps the exact encoded form
            // playback and the cache already use.
            Uri.fromFile(File(data))
        } else {
            ContentUris.withAppendedId(collection, rowId)
        }
        val title = c.getString(cols.title)?.takeIf { it.isNotBlank() }
            ?: data?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: "track"
        return LocalSong(
            path = path,
            title = title,
            artist = c.getString(cols.artist) ?: "unknown artist",
            album = c.getString(cols.album) ?: "",
            durationMs = c.getLong(cols.dur),
            dateAdded = if (cols.added >= 0) c.getLong(cols.added) else 0L,
            uri = uri,
            cover = if (cols.albumId >= 0) albumArtUri(c.getLong(cols.albumId)).orEmpty() else "",
        )
    }

    /** Companion cover image in the track's own folder, if the user keeps one. Kept for a later optional miss path on a visible row — never on the scan path. */
    private fun nearestCover(dir: File?): String? {
        if (dir == null) return null
        val covers = listOf(
            "cover.jpg", "cover.png", "folder.jpg", "folder.png",
            "albumart.jpg", "albumart.png", "front.jpg", "front.png",
        )
        val named = covers.firstNotNullOfOrNull { name ->
            File(dir, name).takeIf { it.isFile }
        }
        if (named != null) return Uri.fromFile(named).toString()
        // Fall back to any image in the same folder (some viewers drop a
        // "folder.jpg"-style file with a different name). If none, leaving it
        // blank lets the embedded-art fallback in LocalArt/StationArtSource win.
        val image = dir.listFiles()?.firstOrNull {
            it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png")
        }
        return image?.let { Uri.fromFile(it).toString() }
    }

    // ── disk cache ──────────────────────────────────────────────────────────

    /**
     * The warm-launch cache. This was a hand-rolled TSV in cacheDir, which is
     * what a preferences store being the wrong shape looks like; it is a table
     * now, so the fuzzy search can query it instead of scanning a parsed list.
     */
    private suspend fun readCache(): List<Station>? =
        runCatching {
            dao.read()
                .map { row ->
                    LocalSong(
                        path = row.path,
                        title = row.title,
                        artist = row.artist,
                        album = row.album,
                        durationMs = row.durationMs,
                        dateAdded = row.dateAdded,
                        // Rows without DATA play by content uri; the stored
                        // uri round-trips verbatim, file rows rebuild encoded.
                        uri = if (row.uri.startsWith("content://")) Uri.parse(row.uri)
                        else Uri.fromFile(File(row.path)),
                        cover = row.cover,
                    ).station
                }
                .takeIf { it.isNotEmpty() }
        }.getOrNull()

    private suspend fun writeCache(songs: List<Station>) {
        runCatching {
            dao.replaceAll(
                songs.map { s ->
                    // The path is the id suffix, never re-derived from the
                    // url: content rows have no file path at all.
                    val path = s.id.removePrefix("local:")
                    LocalSongEntity(
                        songId = s.id,
                        path = path,
                        title = s.name,
                        artist = s.artist,
                        album = s.album,
                        durationMs = s.durationMs,
                        uri = s.url,
                        cover = s.cover,
                        dateAdded = s.dateAdded,
                        sortKey = s.name.lowercase(),
                    )
                }
            )
        }
    }

    // ── removing a song from the phone ──────────────────────────────────────

    private fun pathOf(s: Station): String = s.url.removePrefix("file://").let(Uri::decode)

    /**
     * The MediaStore `content:` uri for a local song item, so it can be shown
     * and deleted through the OS index rather than by reaching into the
     * filesystem. Built from the item's own row id via [ContentUris]: the raw
     * [MediaStore.Audio.Media.getContentUriForPath] uri has no id, and the
     * system's delete sheet rejects id-less collection uris.
     */
    private fun mediaUri(s: Station): Uri? {
        // Content rows already are their own item uri — deletable as-is.
        if (s.url.startsWith("content://")) return Uri.parse(s.url)
        val path = pathOf(s)
        return runCatching {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DATA} = ?",
                arrayOf(path),
                null,
            )?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    c.getLong(0)
                ) else null
            }
        }.getOrNull()
    }

    /**
     * On Android 11+ (API 30) deleting media that the app does not own goes
     * through the system's own confirmation sheet: [MediaStore.createDeleteRequest]
     * hands back a [PendingIntent] the UI launches, and the OS directory is
     * only touched when the user agrees. Returns null on older systems (or when
     * the item cannot be located), where the caller falls back to [removeLocal].
     */
    fun deleteRequest(s: Station): PendingIntent? {
        if (Build.VERSION.SDK_INT < 30) return null
        val uri = mediaUri(s) ?: return null
        return runCatching { MediaStore.createDeleteRequest(resolver, listOf(uri)) }.getOrNull()
    }

    /**
     * Drop a song from the library and the disk cache, and best-effort remove
     * its file (the Android 11+ path does this via the OS dialog; below that it
     * happens here under the legacy write permission).
     */
    fun removeLocal(s: Station) {
        val path = pathOf(s)
        if (_songs.value.any { it.id == s.id }) {
            _songs.value = _songs.value.filterNot { it.id == s.id }
        }
        scope.launch(Dispatchers.IO) {
            runCatching { dao.delete(s.id) }
            if (s.url.startsWith("file://")) {
                runCatching { File(path).delete() }
            }
        }
    }

    companion object {
        /** The audio permission for this OS version. */
        fun audioPermission(): String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE

        fun hasAudioPermission(context: Context): Boolean =
            context.checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED
    }
}
