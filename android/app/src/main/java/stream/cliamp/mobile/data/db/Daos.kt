package stream.cliamp.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface StationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stations: List<StationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(station: StationEntity)

    /** Every persisted station referenced by some list (favs/history/playlists). */
    @Query("SELECT * FROM stations")
    fun all(): Flow<List<StationEntity>>

    /** Resolve snapshot stations (radio/podcast playlist members) back by station id. */
    @Query("SELECT * FROM stations WHERE stationId IN (:ids)")
    suspend fun byStationIds(ids: List<String>): List<StationEntity>
}

@Dao
interface FavoriteDao {
    @Query("""
        SELECT s.* FROM stations s
        JOIN favorites f ON f.url = s.url
        ORDER BY f.position
    """)
    fun all(): Flow<List<StationEntity>>

    /** An indexed lookup, where the JSON list meant a linear scan per row. */
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE url = :url)")
    suspend fun contains(url: String): Boolean

    @Query("SELECT COALESCE(MIN(position), 0) - 1 FROM favorites")
    suspend fun nextTopPosition(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(row: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE url = :url")
    suspend fun remove(url: String)
}

@Dao
interface HistoryDao {
    @Query("""
        SELECT s.* FROM stations s
        JOIN history h ON h.url = s.url
        ORDER BY h.playedAt DESC
        LIMIT :limit
    """)
    fun recent(limit: Int = 60): Flow<List<StationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun touch(row: HistoryEntity)

    /** Trimming is a query, not a list rebuilt and rewritten in full. */
    @Query("DELETE FROM history WHERE url NOT IN (SELECT url FROM history ORDER BY playedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int = 60)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface CustomStationDao {
    @Query("SELECT s.* FROM stations s JOIN custom_stations c ON c.url = s.url ORDER BY c.position")
    fun all(): Flow<List<StationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(row: CustomStationEntity)

    @Query("DELETE FROM custom_stations WHERE url = :url")
    suspend fun remove(url: String)

    @Query("SELECT COALESCE(MIN(position), 0) - 1 FROM custom_stations")
    suspend fun nextTopPosition(): Int
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY position, name")
    fun playlists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist_members ORDER BY slug, position")
    fun members(): Flow<List<PlaylistMemberEntity>>

    @Query("SELECT * FROM playlists WHERE slug = :slug")
    suspend fun find(slug: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE slug = :slug")
    suspend fun delete(slug: String)

    @Query("UPDATE playlists SET name = :name WHERE slug = :slug")
    suspend fun rename(slug: String, name: String)

    @Query("UPDATE playlists SET cover = :cover WHERE slug = :slug")
    suspend fun setCover(slug: String, cover: String)

    @Query("UPDATE playlists SET pinned = :pinned WHERE slug = :slug")
    suspend fun setPinned(slug: String, pinned: Boolean)

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_members WHERE slug = :slug AND songId = :songId)")
    suspend fun hasSong(slug: String, songId: String): Boolean

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_members WHERE slug = :slug")
    suspend fun nextMemberPosition(slug: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMember(row: PlaylistMemberEntity)

    @Query("DELETE FROM playlist_members WHERE slug = :slug AND songId = :songId")
    suspend fun removeMember(slug: String, songId: String)

    @Query("DELETE FROM playlist_members WHERE slug = :slug")
    suspend fun clearMembers(slug: String)

    @Transaction
    suspend fun replaceMembers(slug: String, songIds: List<String>) {
        clearMembers(slug)
        songIds.forEachIndexed { i, id -> addMember(PlaylistMemberEntity(slug, id, i)) }
    }
}

@Dao
interface LocalSongDao {
    @Query("SELECT * FROM local_songs ORDER BY sortKey")
    fun all(): Flow<List<LocalSongEntity>>

    @Query("SELECT * FROM local_songs ORDER BY sortKey")
    suspend fun read(): List<LocalSongEntity>

    @Transaction
    suspend fun replaceAll(rows: List<LocalSongEntity>) {
        clear()
        rows.chunked(400).forEach { upsert(it) }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<LocalSongEntity>)

    @Query("DELETE FROM local_songs")
    suspend fun clear()

    @Query("DELETE FROM local_songs WHERE songId = :songId")
    suspend fun delete(songId: String)
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY label")
    fun all(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY label")
    suspend fun read(): List<ProviderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun remove(id: String)
}

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcast_subscriptions ORDER BY position, title")
    fun subscriptions(): Flow<List<PodcastSubscriptionEntity>>

    @Query("SELECT * FROM podcast_subscriptions ORDER BY position, title")
    suspend fun readSubscriptions(): List<PodcastSubscriptionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM podcast_subscriptions WHERE feedUrl = :feedUrl)")
    suspend fun isSubscribed(feedUrl: String): Boolean

    /** New subscriptions land on top, the way a new favourite does. */
    @Query("SELECT COALESCE(MIN(position), 0) - 1 FROM podcast_subscriptions")
    suspend fun nextTopPosition(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun subscribe(row: PodcastSubscriptionEntity)

    @Query("DELETE FROM podcast_subscriptions WHERE feedUrl = :feedUrl")
    suspend fun unsubscribe(feedUrl: String)

    @Query("SELECT * FROM episode_progress WHERE url = :url")
    suspend fun progress(url: String): EpisodeProgressEntity?

    /** Every position we hold, for badging an episode list in one read. */
    @Query("SELECT * FROM episode_progress")
    fun allProgress(): Flow<List<EpisodeProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(row: EpisodeProgressEntity)

    @Query("DELETE FROM episode_progress WHERE url = :url")
    suspend fun clearProgress(url: String)

    @Query("UPDATE episode_progress SET completed = 1, updatedAt = :now WHERE url = :url")
    suspend fun markCompleted(url: String, now: Long = System.currentTimeMillis())

    /**
     * Started and not finished, newest first. The join is against the stations
     * table that history already writes, so an episode appears here without
     * being stored a second time.
     */
    @Query("""
        SELECT s.* FROM stations s
        JOIN episode_progress p ON p.url = s.url
        WHERE p.completed = 0 AND p.positionMs > 30000
        ORDER BY p.updatedAt DESC
        LIMIT :limit
    """)
    fun continueListening(limit: Int = 30): Flow<List<StationEntity>>
}

/** An album as the index sees it: a directory of tracks, grouped. */
data class SftpAlbumRow(
    val id: String,
    val name: String,
    val artist: String,
    val songCount: Int,
    val year: Int,
)

data class SftpArtistRow(val id: String, val name: String, val albumCount: Int)

@Dao
interface SftpDao {
    /**
     * The three writes a scan makes are blocking rather than suspending: the
     * walk itself is a blocking SFTP call, and the batches come back inside it.
     * Everything the UI reads suspends as usual.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(rows: List<SftpTrackEntity>)

    @Query("DELETE FROM sftp_tracks WHERE accountId = :accountId AND scanId != :scanId")
    fun pruneOlderThan(accountId: String, scanId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun record(row: SftpIndexEntity)

    @Query("DELETE FROM sftp_tracks WHERE accountId = :accountId")
    suspend fun clear(accountId: String)

    @Query("SELECT COUNT(*) FROM sftp_tracks WHERE accountId = :accountId")
    suspend fun count(accountId: String): Int

    /**
     * MAX(mtime) rather than the row's own: an album is as new as its newest
     * file, which is when the folder was copied across.
     */
    @Query("""
        SELECT albumKey AS id, album AS name, artist AS artist,
               COUNT(*) AS songCount, MAX(year) AS year
        FROM sftp_tracks WHERE accountId = :accountId
        GROUP BY albumKey
        ORDER BY MAX(mtime) DESC, album COLLATE NOCASE
    """)
    suspend fun albumsByNewest(accountId: String): List<SftpAlbumRow>

    @Query("""
        SELECT albumKey AS id, album AS name, artist AS artist,
               COUNT(*) AS songCount, MAX(year) AS year
        FROM sftp_tracks WHERE accountId = :accountId
        GROUP BY albumKey
        ORDER BY album COLLATE NOCASE, artist COLLATE NOCASE
    """)
    suspend fun albumsByName(accountId: String): List<SftpAlbumRow>

    @Query("""
        SELECT albumKey AS id, album AS name, artist AS artist,
               COUNT(*) AS songCount, MAX(year) AS year
        FROM sftp_tracks WHERE accountId = :accountId AND artistKey = :artistKey
        GROUP BY albumKey
        ORDER BY MAX(year), album COLLATE NOCASE
    """)
    suspend fun albumsByArtist(accountId: String, artistKey: String): List<SftpAlbumRow>

    @Query("""
        SELECT artistKey AS id, MIN(artist) AS name, COUNT(DISTINCT albumKey) AS albumCount
        FROM sftp_tracks WHERE accountId = :accountId AND artistKey != ''
        GROUP BY artistKey
        ORDER BY name COLLATE NOCASE
    """)
    suspend fun artists(accountId: String): List<SftpArtistRow>

    @Query("""
        SELECT * FROM sftp_tracks WHERE accountId = :accountId AND albumKey = :albumKey
        ORDER BY track, title COLLATE NOCASE
    """)
    suspend fun albumTracks(accountId: String, albumKey: String): List<SftpTrackEntity>

    @Query("SELECT * FROM sftp_tracks WHERE accountId = :accountId AND path = :path")
    suspend fun track(accountId: String, path: String): SftpTrackEntity?

    @Query("SELECT * FROM sftp_index WHERE accountId = :accountId")
    suspend fun index(accountId: String): SftpIndexEntity?

    @Query("DELETE FROM sftp_index WHERE accountId = :accountId")
    suspend fun clearIndex(accountId: String)
}
