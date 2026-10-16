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
import stream.kleeamp.mobile.db.PlayStatEntity
import stream.kleeamp.mobile.db.toEntity
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
    /** Album artist tag; blank when untagged. */
    val albumArtist: String = "",
    /** Track number within its disc, 0 when unknown. */
    val trackNo: Int = 0,
    /** Disc number within its album, 0 when unknown. */
    val discNo: Int = 0,
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
            albumArtist = albumArtist,
            trackNo = trackNo,
            discNo = discNo,
            durationMs = durationMs,
            dateAdded = dateAdded,
        )

    /** Longest sort field is the safe proxy for "starts with title". */
    val sortKey: String get() = title.lowercase()
}

/**
 * Splits a MediaStore TRACK value into (disc, track). Older devices pack
 * disc*1000+track into TRACK with no DISC_NUMBER column; newer ones carry
 * both. A real DISC_NUMBER wins, the packed form is the fallback, and
 * anything missing stays 0 (unknown) so the title tiebreak still orders it.
 */
internal fun decodeDiscTrack(rawTrack: Int, rawDisc: Int): Pair<Int, Int> {
    val disc = when {
        rawDisc > 0 -> rawDisc
        rawTrack >= 1000 -> rawTrack / 1000
        else -> 0
    }
    val track = when {
        rawTrack <= 0 -> 0
        rawTrack >= 1000 -> rawTrack % 1000
        else -> rawTrack
    }
    return disc to track
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
/**
 * MediaStore projection in two tiers. Optional columns (disc number, album
 * artist) are missing on older platforms, and one unknown projection column
 * fails the whole query with IllegalArgumentException — an empty library on
 * old phones. The full tier is probed first, then the guaranteed tier; the
 * per-column -1 defaults at the cursor absorb whatever is absent. Pure
 * column names, so it stays unit-testable without the framework.
 */
internal fun libraryProjection(full: Boolean): Array<String> {
    val base = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.TRACK,
    )
    if (!full) return base
    return base + arrayOf(
        // DISC_NUMBER exists on newer platforms only.
        "disc_number",
        MediaStore.Audio.Media.ALBUM_ARTIST,
    )
}

class LocalLibrary(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
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

        val found = runCatching { querySongs() }.getOrElse { e ->
            if (_songs.value.isEmpty()) _error.value = e.message ?: "could not read the library"
            _songs.value
        }

        // The list (and the spinner going away) always wins over the cache
        // write: rows are up the moment the cursor closes, however slow the
        // write below is. An empty cursor clears a stale list the same way —
        // the cursor is the prune, never a File.exists sweep.
        _songs.value = found
        _loading.value = false

        if (found != cached.orEmpty()) {
            writeCache(found)
        }
        // Favorites, history, counters and resume positions are keyed by
        // playback url: rows cached before the content-uri switch still
        // point at unplayable file:// urls, so re-key them onto the fresh
        // rows by DATA path. A no-op once nothing file:// remains.
        migrateLegacyFileUris(found)
    }

    /**
     * Cursor columns only: no File(), no listFiles(), no exists(), no tag
     * reads, no embedded-art extract. The OS already sorted TITLE for us, so
     * Kotlin never sorts again. Covers are album-art uri strings here;
     * bitmaps decode later, when a row binds.
     */
    private fun querySongs(): List<Station> {
        val out = ArrayList<LocalSong>(256)
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        val order = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE"
        // Probe the full projection first, then the guaranteed tier: a throw
        // from the last tier still surfaces through scan() as the library
        // error instead of silently emptying the list.
        val cursor = runCatching {
            resolver.query(collection, libraryProjection(full = true), selection, null, order)
        }.getOrNull() ?: runCatching {
            resolver.query(collection, libraryProjection(full = false), selection, null, order)
        }.getOrThrow()
        cursor?.use { c ->
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
                track = runCatching { c.getColumnIndex(MediaStore.Audio.Media.TRACK) }.getOrDefault(-1),
                disc = runCatching { c.getColumnIndex("disc_number") }.getOrDefault(-1),
                albumArtist = runCatching { c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) }
                    .getOrDefault(-1),
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
        val track: Int = -1,
        val disc: Int = -1,
        val albumArtist: Int = -1,
    )

    /** One cursor row as a song, strings only. Playlist ids stay local:$path. */
    private fun mapRow(c: Cursor, cols: SongColumns): LocalSong {
        val rowId = c.getLong(cols.id)
        val data = if (cols.data >= 0) c.getString(cols.data) else null
        val path = data?.takeIf { it.isNotBlank() } ?: "media:$rowId"
        // Playback always goes through the MediaStore item uri: raw file
        // paths are refused under scoped storage (Android 10 lists them
        // fine through the cursor, then EACCES on open), while the item uri
        // plays everywhere with the audio permission. The DATA path stays
        // as the stable id, never as the playback url.
        val uri = ContentUris.withAppendedId(collection, rowId)
        val title = c.getString(cols.title)?.takeIf { it.isNotBlank() }
            ?: data?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: "track"
        val rawTrack = if (cols.track >= 0) runCatching { c.getInt(cols.track) }.getOrDefault(0) else 0
        val rawDisc = if (cols.disc >= 0) runCatching { c.getInt(cols.disc) }.getOrDefault(0) else 0
        val (discNo, trackNo) = decodeDiscTrack(rawTrack, rawDisc)
        return LocalSong(
            path = path,
            title = title,
            artist = c.getString(cols.artist) ?: "unknown artist",
            album = c.getString(cols.album) ?: "",
            albumArtist = if (cols.albumArtist >= 0) c.getString(cols.albumArtist).orEmpty() else "",
            trackNo = trackNo,
            discNo = discNo,
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
                        albumArtist = row.albumArtist,
                        trackNo = row.trackNo,
                        discNo = row.discNo,
                        durationMs = row.durationMs,
                        dateAdded = row.dateAdded,
                        // Content rows round-trip verbatim. Legacy file://
                        // rows (cached before the content-uri switch) can no
                        // longer play, so they never paint: the scan below
                        // repopulates them in one pass.
                        uri = Uri.parse(row.uri),
                        cover = row.cover,
                    ).station
                }
                .filter { it.url.startsWith("content://") }
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
                        albumArtist = s.albumArtist,
                        trackNo = s.trackNo,
                        discNo = s.discNo,
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

    /** DATA path for a local song: the stable id first, the legacy url fallback. */
    private fun pathOf(s: Station): String = when {
        s.id.startsWith("local:") -> s.id.removePrefix("local:")
        s.url.startsWith("file://") -> s.url.removePrefix("file://").let(Uri::decode)
        else -> s.url
    }

    /**
     * Re-keys every file:// membership row (favorites, history, play counts,
     * resume positions) onto its fresh content-uri row, matched by DATA path.
     * Song ids never changed, so playlists need no migration; rows whose
     * file is gone are left alone. Memberships keep position/played-at via
     * UPDATE, and the favorites/history joins gain a fresh station snapshot
     * under the new url.
     */
    private suspend fun migrateLegacyFileUris(found: List<Station>) {
        val byPath = found
            .filter { it.source == StationSource.Local && it.id.startsWith("local:") }
            .associate { it.id.removePrefix("local:") to it }
        if (byPath.isEmpty()) return
        val db = KleeampDatabase.get(appContext)
        for (old in db.favorites().fileUrls()) migrateFavoriteUrl(db, byPath, old)
        for (old in db.history().fileUrls()) migrateHistoryUrl(db, byPath, old)
        for (old in db.stats().fileUrls()) migrateStatsUrl(db, byPath, old)
        for (old in db.podcasts().fileUrls()) migrateProgressUrl(db, byPath, old)
        // DataStore windows (queue, last station, widget) keep JSON snapshots
        // that the Room migration above never touches.
        runCatching {
            stream.kleeamp.mobile.prefs.Prefs(appContext).healLegacyLocalStations(byPath)
        }
    }

    /** Fresh row for a legacy file:// url, matched by DATA path. */
    private fun songFor(
        byPath: Map<String, Station>,
        oldUrl: String,
    ): Station? {
        if (!oldUrl.startsWith("file://")) return null
        return byPath[decodePath(oldUrl.removePrefix("file://"))]
    }

    /** Fresh station snapshots under the new url; the joins need them. */
    private suspend fun adoptSnapshot(db: KleeampDatabase, song: Station, oldUrl: String) {
        runCatching {
            db.stations().upsert(song.toEntity())
            db.stations().deleteByUrl(oldUrl)
        }
    }

    private suspend fun migrateFavoriteUrl(
        db: KleeampDatabase,
        byPath: Map<String, Station>,
        old: String,
    ) {
        val song = songFor(byPath, old) ?: return
        runCatching {
            if (db.favorites().contains(song.url)) db.favorites().remove(old)
            else db.favorites().rekey(old, song.url)
        }
        adoptSnapshot(db, song, old)
    }

    private suspend fun migrateHistoryUrl(
        db: KleeampDatabase,
        byPath: Map<String, Station>,
        old: String,
    ) {
        val song = songFor(byPath, old) ?: return
        // History has no position worth a merge: the surviving row wins.
        runCatching {
            if (db.history().contains(song.url)) db.history().remove(old)
            else db.history().rekey(old, song.url)
        }
        adoptSnapshot(db, song, old)
    }

    /** Play counts merge (plays add up, latest stamp wins). */
    private suspend fun migrateStatsUrl(
        db: KleeampDatabase,
        byPath: Map<String, Station>,
        old: String,
    ) {
        val song = songFor(byPath, old) ?: return
        runCatching {
            val cur = db.stats().peek(song.url)
            val legacy = db.stats().peek(old)
            if (cur == null && legacy != null) {
                db.stats().rekey(old, song.url)
            } else {
                if (legacy != null && cur != null) {
                    db.stats().upsert(
                        PlayStatEntity(
                            song.url,
                            cur.plays + legacy.plays,
                            maxOf(cur.lastPlayedAt, legacy.lastPlayedAt),
                        )
                    )
                }
                db.stats().remove(old)
            }
        }
    }

    /** Resume keeps the newer position when both rows exist. */
    private suspend fun migrateProgressUrl(
        db: KleeampDatabase,
        byPath: Map<String, Station>,
        old: String,
    ) {
        val song = songFor(byPath, old) ?: return
        runCatching {
            val cur = db.podcasts().progress(song.url)
            if (cur == null) {
                db.podcasts().rekey(old, song.url)
            } else {
                val legacy = db.podcasts().progress(old)
                if (legacy != null && legacy.updatedAt > cur.updatedAt) {
                    db.podcasts().saveProgress(legacy.copy(url = song.url))
                }
                db.podcasts().clearProgress(old)
            }
        }
    }

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
        return contentUriForDataPath(appContext, pathOf(s))
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

        /**
         * MediaStore item uri for a DATA path, or null when the row is gone.
         * The single lookup behind delete, legacy playback and migration.
         * Blocking: call off Main.
         */
        fun contentUriForDataPath(context: Context, dataPath: String): Uri? {
            if (dataPath.isBlank()) return null
            return runCatching {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    "${MediaStore.Audio.Media.DATA} = ?",
                    arrayOf(dataPath),
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        c.getLong(0),
                    ) else null
                }
            }.getOrNull()
        }

        /**
         * Content uri for a legacy `file://` song url, or null when the row
         * is gone. Playback safety net for rows cached before the
         * content-uri switch (favorites, history, restored queues).
         * Blocking: call off Main.
         */
        fun contentUriForLegacyFile(context: Context, fileUrl: String): Uri? {
            if (!fileUrl.startsWith("file://")) return null
            return contentUriForDataPath(
                context,
                android.net.Uri.decode(fileUrl.removePrefix("file://")),
            )
        }
    }
}
