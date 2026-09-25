package stream.kleeamp.mobile.library

import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import java.io.File

/** Playlist file flavor. */
enum class PlaylistKind { M3U, PLS }

/** One playlist-file entry: a path/URL plus optional EXTINF metadata. */
data class PlaylistEntry(
    val location: String,
    val title: String = "",
    val durationSec: Long = 0,
)

/** Match outcome: playable stations plus the unresolvable count. */
data class PlaylistMatch(
    val matched: List<Station>,
    val missing: Int,
)

/** Flavor by filename, falling back to sniffing the content. */
fun detectPlaylistKind(filename: String, text: String): PlaylistKind? {
    val lower = filename.lowercase()
    if (lower.endsWith(".m3u") || lower.endsWith(".m3u8")) return PlaylistKind.M3U
    if (lower.endsWith(".pls")) return PlaylistKind.PLS
    val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    return when {
        first == null -> null
        first.equals("#EXTM3U", ignoreCase = true) -> PlaylistKind.M3U
        first.equals("[playlist]", ignoreCase = true) -> PlaylistKind.PLS
        else -> null
    }
}

/**
 * Extended M3U: `#EXTINF:<secs>,artist - title` lines name the entry that
 * follows; anything else starting with `#` is a comment. Relative paths
 * resolve against [baseDir] (the file's own folder on import).
 */
fun parseM3u(text: String, baseDir: String = ""): List<PlaylistEntry> {
    val out = ArrayList<PlaylistEntry>()
    var pending = Pending("", 0L)
    for (raw in text.lineSequence()) {
        pending = parseM3uLine(raw.trim(), baseDir, out, pending)
    }
    return out
}

/** Pending EXTINF metadata waiting for its entry line. */
private data class Pending(val title: String, val secs: Long)

/** One M3U line folded into the output, returning the pending metadata. */
private fun parseM3uLine(
    line: String,
    baseDir: String,
    out: MutableList<PlaylistEntry>,
    pending: Pending,
): Pending {
    if (line.isEmpty() || isIgnorableComment(line)) return pending
    if (line.startsWith("#EXTINF:", ignoreCase = true)) return parseExtInf(line)
    out += PlaylistEntry(locate(line, baseDir), pending.title, pending.secs)
    return Pending("", 0L)
}

private fun isIgnorableComment(line: String): Boolean =
    line.startsWith("#") && !line.startsWith("#EXTINF:", ignoreCase = true)

private fun parseExtInf(line: String): Pending {
    val body = line.substringAfter(':')
    return Pending(
        title = body.substringAfter(',', "").trim(),
        secs = body.substringBefore(',').trim().toLongOrNull()?.takeIf { it >= 0 } ?: 0L,
    )
}

private fun locate(line: String, baseDir: String): String =
    if (looksLikeUrl(line) || File(line).isAbsolute || baseDir.isBlank()) line
    else File(baseDir, line).path

/** PLS: FileN/TitleN/LengthN triples under `[playlist]`. */
fun parsePls(text: String): List<PlaylistEntry> {
    val props = text.lineSequence()
        .map { it.trim() }
        .filter { line ->
            line.isNotEmpty() && !line.startsWith(";") && !line.startsWith("#") &&
                !line.startsWith("[") && line.contains('=')
        }
        .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim() }
    val count = props["numberofentries"]?.toIntOrNull() ?: 0
    return (1..count).mapNotNull { i ->
        props["file$i"]?.takeIf { it.isNotEmpty() }?.let { location ->
            PlaylistEntry(location, props["title$i"].orEmpty(), props["length$i"]?.toLongOrNull() ?: 0L)
        }
    }
}

/** M3U text for [stations]: EXTINF artist/title lines plus locations. */
fun renderM3u(stations: List<Station>): String = buildString {
    appendLine("#EXTM3U")
    for (s in stations) {
        val secs = if (s.durationMs > 0) s.durationMs / 1000 else -1
        val title = listOf(s.artist, s.name).filter { it.isNotBlank() }.joinToString(" - ")
        appendLine("#EXTINF:$secs,$title")
        appendLine(s.url.removePrefix("file://"))
    }
}

/**
 * Matches file entries against the live library. Local entries hit by
 * absolute path first, then by unique filename; http(s) entries become
 * playable URL stations like hand-added customs. Anything else counts as
 * missing instead of failing the import.
 */
fun matchEntries(entries: List<PlaylistEntry>, localSongs: List<Station>): PlaylistMatch {
    val byPath = localSongs.associateBy { decodePath(it.url.removePrefix("file://")) }
    val byName = localSongs.groupBy { File(it.url.removePrefix("file://")).name }
    val matched = entries.mapNotNull { matchOne(it, byPath, byName) }
    return PlaylistMatch(matched, entries.size - matched.size)
}

/** One entry resolved, or null when nothing playable matches it. */
private fun matchOne(
    entry: PlaylistEntry,
    byPath: Map<String, Station>,
    byName: Map<String, List<Station>>,
): Station? {
    val loc = entry.location.trim()
    return when {
        loc.isEmpty() -> null
        looksLikeUrl(loc) -> urlStation(loc, entry.title)
        else -> {
            val direct = byPath[loc] ?: byPath[File(loc).path]
            direct ?: byName[File(loc).name]?.takeIf { it.size == 1 }?.get(0)
        }
    }
}

/** A playable station for a bare URL entry, like a hand-added custom. */
fun urlStation(url: String, title: String): Station {
    val label = title.ifBlank {
        runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: url
    }
    return Station(id = "custom:$url", name = label, url = url, source = StationSource.Custom)
}

/** Ordered ids with repeats dropped (first occurrence wins) plus the drop count. */
fun dedupeIds(ids: List<String>): Pair<List<String>, Int> {
    val seen = LinkedHashSet<String>()
    var dropped = 0
    for (id in ids) {
        if (!seen.add(id)) dropped++
    }
    return Pair(seen.toList(), dropped)
}

private fun looksLikeUrl(s: String) =
    s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

/**
 * Percent-decoding for file paths, pure JVM so unit tests can reach it
 * (`android.net.Uri` is stubbed under unit tests). Leaves `+` alone,
 * unlike form decoding.
 */
internal fun decodePath(s: String): String {
    if ('%' !in s) return s
    val bytes = s.toByteArray(Charsets.UTF_8)
    val out = java.io.ByteArrayOutputStream(bytes.size)
    var i = 0
    while (i < bytes.size) {
        val b = bytes[i].toInt() and 0xFF
        if (b == '%'.code && i + 2 < bytes.size) {
            val v = String(bytes, i + 1, 2, Charsets.US_ASCII).toIntOrNull(16)
            if (v != null) {
                out.write(v)
                i += 3
                continue
            }
        }
        out.write(b)
        i++
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}
