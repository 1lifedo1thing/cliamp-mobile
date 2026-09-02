package stream.cliamp.mobile.data

import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * A song picked out of the local Music folder. The [station] form is what the
 * rest of the app plays: a local file is just a Station whose `url` is a file
 * URI, so the whole playback pipeline behaves exactly as it does for a stream.
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
 * Reads audio files straight off the Music folder on disk, recursing into
 * subfolders, rather than querying MediaStore. Nothing here is cached to disk —
 * we just walk the tree on demand and keep a plain StateFlow for the UI.
 */
class LocalLibrary {

    private val _songs = MutableStateFlow<List<Station>>(emptyList())
    val songs: StateFlow<List<Station>> = _songs.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val audioExts = setOf("mp3", "m4a", "mp4", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "aiff", "aif", "mid", "midi")

    fun refresh() {
        _loading.value = true
        Thread {
            val found = runCatching { querySongs() }.getOrElse { e ->
                _error.value = e.message ?: "could not read the library"
                emptyList()
            }
            _songs.value = found
            _loading.value = false
        }.start()
    }

    private fun querySongs(): List<Station> {
        val root = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC).absolutePath
        ).takeIf { it.isDirectory }
            ?: File(android.os.Environment.getExternalStorageDirectory(), "Music").takeIf { it.isDirectory }
            ?: return emptyList()
        val walker = FileWalker(listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "albumart.jpg", "albumart.png", "front.jpg", "front.png"))
        walker.visit(root)
        val list = walker.songs.map { it.station }.sortedBy { it.name.lowercase() }
        return list
    }

    private inner class FileWalker(coverNames: List<String>) {
        private val covers = coverNames.toSet()
        val songs = mutableListOf<LocalSong>()

        fun visit(dir: File) {
            val files = dir.listFiles() ?: return
            files.sortedBy { it.name.lowercase() }.forEach { f ->
                if (f.isDirectory) visit(f)
                else if (f.isFile && isAudio(f.name)) index(f)
            }
        }

        private fun index(f: File) {
            val meta = readMeta(f)
            val cover = nearestCover(f.parentFile ?: return).orEmpty()
            songs += LocalSong(
                path = f.absolutePath,
                title = meta.title ?: f.nameWithoutExtension,
                artist = meta.artist ?: "unknown artist",
                album = meta.album ?: "",
                durationMs = meta.durationMs,
                uri = Uri.fromFile(f),
                cover = cover,
            )
        }

        private fun nearestCover(dir: File): String? {
            val named = covers.firstNotNullOfOrNull { name ->
                File(dir, name).takeIf { it.isFile }
            }
            if (named != null) return Uri.fromFile(named).toString()
            // fall back to any image in the same folder (embedded art lives here)
            val image = dir.listFiles()?.firstOrNull {
                it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png")
            }
            return image?.let { Uri.fromFile(it).toString() }
        }
    }

    private fun isAudio(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in audioExts

    private data class Meta(val title: String?, val artist: String?, val album: String?, val durationMs: Long)

    private fun readMeta(f: File): Meta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(f.absolutePath)
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            Meta(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = dur,
            )
        } catch (_: Exception) {
            Meta(null, null, null, 0L)
        } finally {
            runCatching { retriever.release() }
        }
    }
}