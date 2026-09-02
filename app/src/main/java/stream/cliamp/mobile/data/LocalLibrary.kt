package stream.cliamp.mobile.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
class LocalLibrary(context: Context) {

    private val resolver = context.contentResolver
    private val cacheFile = File(context.cacheDir, "local_library.tsv")

    private val _songs = MutableStateFlow<List<Station>>(emptyList())
    val songs: StateFlow<List<Station>> = _songs.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refresh() {
        // Serve whatever we already have now. On a warm launch that is the disk
        // cache, so the list paints instantly and never flashes a scan message;
        // on a cold install the cache is empty and the UI shows "scanning…"
        // until the MediaStore query below fills it.
        val cached = readCache()
        if (_songs.value.isEmpty() && !cached.isNullOrEmpty()) {
            _songs.value = cached
        }
        _loading.value = _songs.value.isEmpty()
        _error.value = null
        Thread {
            val found = runCatching { querySongs() }.getOrElse { e ->
                if (_songs.value.isEmpty()) _error.value = e.message ?: "could not read the library"
                _songs.value
            }
            if (found.isNotEmpty()) {
                _songs.value = found
                writeCache(found)
            }
            _loading.value = false
        }.start()
    }

    private fun querySongs(): List<Station> {
        val out = ArrayList<LocalSong>(256)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
        )
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE",
        )?.use { c ->
            val cId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val cTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val cArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val cAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val cDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
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
                val cover = nearestCover(file.parentFile).orEmpty()
                out += LocalSong(
                    path = data,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = c.getLong(cDur),
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

    private fun readCache(): List<Station>? {
        if (!cacheFile.isFile) return null
        return runCatching {
            val out = ArrayList<Station>(256)
            cacheFile.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parts = line.split('\u0001')
                    if (parts.size < 6) continue
                    val path = parts[0]
                    if (!File(path).isFile) continue // dropped since last time
                    out += LocalSong(
                        path = path,
                        title = parts[1],
                        artist = parts[2],
                        album = parts[3],
                        durationMs = parts[4].toLongOrNull() ?: 0L,
                        uri = Uri.fromFile(File(path)),
                        cover = parts[5],
                    ).station
                }
            }
            out
        }.getOrNull()
    }

    private fun writeCache(songs: List<Station>) {
        runCatching {
            cacheFile.bufferedWriter().use { w ->
                for (s in songs) {
                    val path = s.url.removePrefix("file://")
                    w.write(
                        listOf(
                            path,
                            s.name,
                            s.artist,
                            s.album,
                            s.durationMs.toString(),
                            s.cover,
                        ).joinToString("\u0001")
                    )
                    w.write("\n")
                }
            }
        }
    }
}