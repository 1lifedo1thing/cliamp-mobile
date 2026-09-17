package stream.kleeamp.mobile.data.provider

import android.util.Log
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient

private const val TAG = "kleeamp/sftp"

/** One audio file found on the server, with what its path says about it. */
data class ScannedTrack(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    /** The album's directory: stable, unique, and what tracks group by. */
    val albumKey: String,
    /** Case-folded artist name, so `Boards of Canada` and `boards of canada` are one. */
    val artistKey: String,
    val track: Int,
    val year: Int,
    val size: Long,
    val mtime: Long,
    val ext: String,
)

/**
 * What Media3 can actually decode, which is narrower than what people keep in a
 * music folder. Anything else in the tree is skipped rather than indexed into a
 * library entry that fails the moment it is tapped.
 */
val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus",
    "wav", "wave", "mka", "mp4", "3gp", "ts", "aif", "aiff",
)

/**
 * Walks an account's configured folders and reports every audio file it finds.
 *
 * There are no tags here. Reading them would mean pulling the header of every
 * file over the wire - tens of thousands of round trips for a real library -
 * so the layout is treated as the metadata, which is exactly what
 * `Artist/Album/01 - Title.flac` already encodes. What the file actually
 * contains is read once, by the extractor, when the track is played.
 *
 * [onBatch] is handed tracks as they are found rather than at the end, so the
 * library fills in while a large tree is still being walked.
 */
class SftpScan(
    private val folders: List<String>,
    private val onBatch: (List<ScannedTrack>) -> Unit,
    private val onProgress: (found: Int, where: String) -> Unit = { _, _ -> },
) {

    private var found = 0
    private var directories = 0
    private val visited = HashSet<String>()
    private val batch = ArrayList<ScannedTrack>(BATCH)

    /** Total tracks found. Throws whatever the connection threw. */
    fun run(sftp: SFTPClient): Int {
        for (folder in folders) {
            val root = runCatching { sftp.canonicalize(folder) }.getOrDefault(folder).trimEnd('/')
            if (!visited.add(root)) continue
            walk(sftp, root, root, 0)
        }
        flush()
        return found
    }

    private fun walk(sftp: SFTPClient, root: String, dir: String, depth: Int) {
        if (depth > MAX_DEPTH || found >= MAX_FILES || directories >= MAX_DIRECTORIES) return
        directories++
        onProgress(found, dir)

        val entries = runCatching { sftp.ls(dir) }.getOrElse {
            // An unreadable subdirectory is normal (permissions, a stale mount)
            // and is not a reason to abandon the rest of the library.
            Log.w(TAG, "skipping $dir: ${it.message}")
            return
        }

        val subdirectories = ArrayList<String>()
        for (entry in entries.sortedBy { it.name.lowercase() }) {
            if (entry.name.startsWith(".")) continue
            when (kind(sftp, entry)) {
                Kind.Directory -> subdirectories += entry.path
                Kind.File -> {
                    val extension = entry.name.substringAfterLast('.', "").lowercase()
                    if (extension !in AUDIO_EXTENSIONS) continue
                    if (found >= MAX_FILES) return
                    found++
                    batch += describe(
                        root = root,
                        path = entry.path,
                        size = entry.attributes.size,
                        mtime = entry.attributes.mtime,
                    )
                    if (batch.size >= BATCH) flush()
                }
                Kind.Other -> Unit
            }
        }
        for (child in subdirectories) {
            val canonical = runCatching { sftp.canonicalize(child) }.getOrDefault(child)
            // Symlinks make a music tree a graph; without this, one pointing at
            // an ancestor walks forever.
            if (!visited.add(canonical)) continue
            walk(sftp, root, child, depth + 1)
        }
    }

    private enum class Kind { Directory, File, Other }

    private fun kind(sftp: SFTPClient, entry: RemoteResourceInfo): Kind = when {
        entry.isDirectory -> Kind.Directory
        entry.isRegularFile -> Kind.File
        // `ls` reports what lstat saw, so a symlinked album folder is neither
        // until it is followed.
        entry.attributes.type == FileMode.Type.SYMLINK -> runCatching {
            when (sftp.stat(entry.path).type) {
                FileMode.Type.DIRECTORY -> Kind.Directory
                FileMode.Type.REGULAR -> Kind.File
                else -> Kind.Other
            }
        }.getOrDefault(Kind.Other)
        else -> Kind.Other
    }

    private fun flush() {
        if (batch.isEmpty()) return
        onBatch(batch.toList())
        batch.clear()
    }

    private companion object {
        const val MAX_DEPTH = 8
        const val MAX_FILES = 40_000
        const val MAX_DIRECTORIES = 20_000
        const val BATCH = 250
    }
}

/**
 * Reads a file's path as `<root>/<artist>/<album>/<track> - <title>.<ext>`,
 * degrading to whatever of that is actually present. A flat folder of loose
 * files gets the folder's own name as the album and no artist; a single level
 * under the root is read as `Artist - Album` when it is punctuated that way,
 * because that is how a lot of libraries that never had an artist level look.
 */
fun describe(root: String, path: String, size: Long = 0, mtime: Long = 0): ScannedTrack {
    val directory = path.substringBeforeLast('/', "")
    val filename = path.substringAfterLast('/')
    val extension = filename.substringAfterLast('.', "").lowercase()
    val stem = filename.substringBeforeLast('.', filename)

    val (track, titleFromName) = splitTrackNumber(stem)
    val segments = directory.removePrefix(root).split('/').filter { it.isNotBlank() }

    var artist = ""
    var albumSource = when {
        segments.isEmpty() -> root.substringAfterLast('/').ifBlank { "music" }
        else -> segments.last()
    }
    if (segments.size >= 2) {
        artist = segments[segments.size - 2].trim()
    } else if (segments.size == 1) {
        val split = albumSource.split(" - ", limit = 2)
        // `1973 - Dark Side` is a year and an album, not an artist and an
        // album, and it is punctuated identically. The year split below owns it.
        val leadsWithYear = split.firstOrNull()?.trim()?.toIntOrNull() != null
        if (split.size == 2 && !leadsWithYear && split[0].isNotBlank() && split[1].isNotBlank()) {
            artist = split[0].trim()
            albumSource = split[1]
        }
    }

    val (album, year) = splitYear(albumSource)

    return ScannedTrack(
        path = path,
        title = titleFromName.ifBlank { stem }.trim(),
        artist = artist,
        album = album,
        albumKey = directory,
        artistKey = artist.lowercase(),
        track = track,
        year = year,
        size = size,
        mtime = mtime,
        ext = extension,
    )
}

/**
 * `07 - Money` and `07. Money` and `07 Money` all mean the same thing. Capped
 * at three digits so a title that opens with a year - `1999 Party` - keeps it.
 */
private val TRACK_NUMBER = Regex("""^(\d{1,3})(?:\s*[-–—._)\]]+\s*|\s+)(\S.*)$""")

private fun splitTrackNumber(stem: String): Pair<Int, String> {
    val m = TRACK_NUMBER.find(stem.trim()) ?: return 0 to stem
    val number = m.groupValues[1].toIntOrNull() ?: return 0 to stem
    return number to m.groupValues[2]
}

/**
 * A bracketed year is unambiguous, so the separator after it is optional. A bare
 * one is not - `2001 A Space Odyssey` is a title - so it has to be punctuated
 * as a prefix before it counts as a year.
 */
private val YEAR_BRACKETED = Regex("""^[(\[]((?:19|20)\d{2})[)\]]\s*[-–—._]?\s*(\S.*)$""")
private val YEAR_LEADING = Regex("""^((?:19|20)\d{2})\s*[-–—._]\s*(\S.*)$""")
private val YEAR_TRAILING = Regex("""^(.+?)\s*[(\[]((?:19|20)\d{2})[)\]]\s*$""")

/** `1973 - Dark Side` and `Dark Side (1973)` both give the album its year back. */
private fun splitYear(name: String): Pair<String, Int> {
    val trimmed = name.trim()
    YEAR_BRACKETED.find(trimmed)?.let {
        return it.groupValues[2].trim() to it.groupValues[1].toInt()
    }
    YEAR_LEADING.find(trimmed)?.let {
        return it.groupValues[2].trim() to it.groupValues[1].toInt()
    }
    YEAR_TRAILING.find(trimmed)?.let {
        return it.groupValues[1].trim() to it.groupValues[2].toInt()
    }
    return trimmed to 0
}

/**
 * Directories worth offering when the folder field was left empty.
 *
 * A first connection is the worst moment to ask someone for an absolute path
 * they have not thought about in years, so the probe looks in the handful of
 * places music actually lives and fills the field in with whatever it finds.
 */
fun suggestMusicFolders(sftp: SFTPClient): List<String> {
    val home = runCatching { sftp.canonicalize(".") }.getOrDefault("").trimEnd('/')
    val candidates = buildList {
        if (home.isNotBlank()) {
            addAll(listOf("Music", "music", "Musik", "Musique", "Media/Music", "media/music").map { "$home/$it" })
        }
        addAll(listOf("/srv/music", "/mnt/music", "/media/music", "/data/music", "/music", "/var/lib/music"))
    }
    return candidates.filter { holdsAudio(sftp, it) }.distinct()
}

/** Whether a configured folder is actually a folder on the far end. */
fun isDirectory(sftp: SFTPClient, path: String): Boolean = runCatching {
    sftp.statExistence(path)?.type == FileMode.Type.DIRECTORY
}.getOrDefault(false)

/** Cheap two-level look for anything playable, so an empty folder is not offered. */
private fun holdsAudio(sftp: SFTPClient, path: String): Boolean = runCatching {
    if (!isDirectory(sftp, path)) return@runCatching false
    val top = sftp.ls(path).filterNot { it.name.startsWith(".") }
    top.any { it.isRegularFile && it.isAudio } ||
        top.asSequence()
            .filter { it.isDirectory }
            .take(12)
            .any { child ->
                runCatching { sftp.ls(child.path).any { it.isRegularFile && it.isAudio } }
                    .getOrDefault(false)
            }
}.getOrDefault(false)

private val RemoteResourceInfo.isAudio: Boolean
    get() = name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
