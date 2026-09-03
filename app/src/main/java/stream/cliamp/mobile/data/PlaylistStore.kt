package stream.cliamp.mobile.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import stream.cliamp.mobile.data.db.CliampDatabase
import stream.cliamp.mobile.data.db.PlaylistEntity
import stream.cliamp.mobile.data.db.PlaylistMemberEntity

/**
 * Persists user playlists. A playlist is a small [Station] (source Local): its
 * `slug` is the stable id, `name` the title, `cover` an artwork URI, and `meta`
 * a human "N songs" line. Members are remembered as `local:<id>` strings so
 * they survive a MediaStore re-scan (URIs can move). The ordered song list for
 * a playlist is resolved against the live library when you open it.
 */
class PlaylistStore(private val context: Context) {

    data class Playlist(
        val station: Station,
        /** Ordered member ids (`local:<id>`), matching [LocalLibrary] ids. */
        val songIds: List<String>,
    )

    private val db by lazy { CliampDatabase.get(context) }
    private val dao by lazy { db.playlists() }

    /**
     * Rows and their members, joined in memory from two flows.
     *
     * This replaced three JSON blobs kept in step by hand: a playlist list, a
     * slug-to-members map and a pinned set. Deleting a playlist now takes its
     * members with it by foreign key, which the blobs had to remember to do.
     */
    val playlists: Flow<List<Playlist>> =
        combine(dao.playlists(), dao.members()) { lists, members ->
            val bySlug = members.groupBy { it.slug }
            lists.map { pl ->
                Playlist(
                    station = pl.toStation(),
                    songIds = bySlug[pl.slug].orEmpty().sortedBy { it.position }.map { it.songId },
                )
            }
        }

    val pinnedSlugs: Flow<Set<String>> =
        dao.playlists().map { rows -> rows.filter { it.pinned }.map { it.slug }.toSet() }

    suspend fun create(name: String, cover: String = "") {
        val clean = name.trim().ifEmpty { return }
        val slug = slugify(clean)
        if (dao.find(slug) != null) return
        dao.upsert(PlaylistEntity(slug = slug, name = clean, cover = cover))
    }

    suspend fun rename(slug: String, name: String) {
        val clean = name.trim().ifEmpty { return }
        dao.rename(slug, clean)
    }

    suspend fun delete(slug: String) = dao.delete(slug)

    suspend fun setPinned(slug: String, pinned: Boolean) = dao.setPinned(slug, pinned)

    suspend fun setCover(slug: String, cover: String) = dao.setCover(slug, cover)

    suspend fun addSong(slug: String, songId: String): Boolean {
        if (dao.hasSong(slug, songId)) return false
        dao.addMember(PlaylistMemberEntity(slug, songId, dao.nextMemberPosition(slug)))
        return true
    }

    suspend fun removeSong(slug: String, songId: String) = dao.removeMember(slug, songId)

    suspend fun setOrder(slug: String, songIds: List<String>) = dao.replaceMembers(slug, songIds)

    /** Resolved, playable songs for a playlist against the current library. */
    fun songsOf(playlist: Playlist, library: List<Station>): List<Station> {
        val byId = library.associateBy { it.id }
        return playlist.songIds.mapNotNull(byId::get)
    }
}

private fun PlaylistEntity.toStation() = Station(
    id = "playlist:$slug",
    name = name,
    url = "cliamp-playlist://$slug",
    source = StationSource.Custom,
    slug = slug,
    cover = cover,
)

private fun slugify(name: String): String =
    name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("").trim('-').replace(Regex("-+"), "-")
        .ifEmpty { "playlist-" + System.currentTimeMillis() }
