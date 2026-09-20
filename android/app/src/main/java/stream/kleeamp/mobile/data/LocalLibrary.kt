package stream.kleeamp.mobile.data

import android.Manifest
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import stream.kleeamp.mobile.data.db.KleeampDatabase
import stream.kleeamp.mobile.data.db.LocalSongEntity
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

    private val _songs = MutableStateFlow<List<Station>>(emptyList())
    val songs: StateFlow<List<Station>> = _songs.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val scanLock = Any()
    private var scanJob: Job? = null

    fun refresh() {
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

    private suspend fun scan() {
        // Cache read and MediaStore scan both live on this background
        // context so a warm launch's per-song File.isFile check never
        // janks the UI.
        val cached = readCache()
        if (_songs.value.isEmpty() && !cached.isNullOrEmpty()) {
            _songs.value = cached
        }
        _loading.value = _songs.value.isEmpty()
        val found = runCatching { querySongs() }.getOrElse { e ->
            if (_songs.value.isEmpty()) _error.value = e.message ?: "could not read the library"
            _songs.value
        }
        if (found.isNotEmpty()) {
            _songs.value = found
            writeCache(found)
        }
        _loading.value = false
    }

    private fun querySongs(): List<Station> {
        val out = ArrayList<LocalSong>(256)
        // Cover art is looked up per song, but every track in a folder shares
        // that folder's cover and calling listFiles() once per song is what
        // makes a first scan crawl. Memoise the result per directory so a big
        // library is one stat per song plus one directory listing per folder.
        val coverByDir = HashMap<String, String?>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATA,
        )
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            MediaStore.Audio.Media.IS_MUSIC + " != 0",
            null,
            MediaStore.Audio.Media.TITLE + " COLLATE NOCASE",
        )?.use { c ->
            val cId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val cTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val cArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val cAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val cDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val cAdded = runCatching { c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED) }.getOrDefault(-1)
            // DATA is deprecated but still populated on current devices, and it
            // is what lets us play via a readable file path and pull on-disk
            // cover art. When it is absent a track is simply skipped.
            val cData = runCatching { c.getColumnIndex(MediaStore.Audio.Media.DATA) }.getOrNull() ?: -1
            while (c.moveToNext()) {
                val id = c.getLong(cId)
                val data = if (cData >= 0) c.getString(cData) else null
                if (data.isNullOrBlank()) continue
                val file = File(data)
                if (!file.isFile) continue
                val artist = c.getString(cArtist) ?: "unknown artist"
                val album = c.getString(cAlbum) ?: ""
                val title = c.getString(cTitle)?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
                val dir = file.parentFile?.path.orEmpty()
                val cover = coverByDir.getOrPut(dir) { nearestCover(file.parentFile) }.orEmpty()
                out += LocalSong(
                    path = data,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = c.getLong(cDur),
                    dateAdded = if (cAdded >= 0) c.getLong(cAdded) else 0L,
                    uri = Uri.fromFile(file),
                    cover = cover,
                )
            }
        }
        return out.map { it.station }.sortedBy { it.name.lowercase() }
    }

    /** Companion cover image in the track's own folder, if the user keeps one. */
    private fun nearestCover(dir: File?): String? {
        if (dir == null) return null
        val covers = listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "albumart.jpg", "albumart.png", "front.jpg", "front.png")
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
                .filter { File(it.path).isFile } // dropped since last scan
                .map { row ->
                    LocalSong(
                        path = row.path,
                        title = row.title,
                        artist = row.artist,
                        album = row.album,
                        durationMs = row.durationMs,
                        dateAdded = row.dateAdded,
                        uri = Uri.fromFile(File(row.path)),
                        cover = row.cover,
                    ).station
                }
                .takeIf { it.isNotEmpty() }
        }.getOrNull()

    private suspend fun writeCache(songs: List<Station>) {
        runCatching {
            dao.replaceAll(
                songs.map { s ->
                    val path = s.url.removePrefix("file://").let(Uri::decode)
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