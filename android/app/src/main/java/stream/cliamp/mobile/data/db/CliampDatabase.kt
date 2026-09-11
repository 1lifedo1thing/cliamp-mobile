package stream.cliamp.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        StationEntity::class,
        FavoriteEntity::class,
        HistoryEntity::class,
        CustomStationEntity::class,
        PlaylistEntity::class,
        PlaylistMemberEntity::class,
        LocalSongEntity::class,
        ProviderEntity::class,
        PodcastSubscriptionEntity::class,
        EpisodeProgressEntity::class,
        SftpTrackEntity::class,
        SftpIndexEntity::class,
        KvCacheEntity::class,
        PodcastFeedCacheEntity::class,
        PlayStatEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class CliampDatabase : RoomDatabase() {
    abstract fun stations(): StationDao
    abstract fun favorites(): FavoriteDao
    abstract fun history(): HistoryDao
    abstract fun customStations(): CustomStationDao
    abstract fun playlists(): PlaylistDao
    abstract fun localSongs(): LocalSongDao
    abstract fun providers(): ProviderDao
    abstract fun podcasts(): PodcastDao
    abstract fun sftp(): SftpDao
    abstract fun cache(): CacheDao
    abstract fun stats(): StatsDao

    companion object {
        @Volatile private var instance: CliampDatabase? = null

        /**
         * Podcasts, added additively. Two new tables and nothing touched, so
         * this is a pair of CREATEs rather than a rebuild - and it is a real
         * migration rather than a destructive fallback because v1 holds
         * favourites, playlists and provider accounts that cannot be re-derived.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcast_subscriptions` (" +
                        "`feedUrl` TEXT NOT NULL, `showId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`author` TEXT NOT NULL, `artwork` TEXT NOT NULL, `genre` TEXT NOT NULL, " +
                        "`episodeCount` INTEGER NOT NULL, `description` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, `subscribedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`feedUrl`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `episode_progress` (" +
                        "`url` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, `completed` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`url`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_episode_progress_updatedAt` " +
                        "ON `episode_progress` (`updatedAt`)"
                )
            }
        }

        /**
         * Local songs grow a "recently added" column, so the picker's
         * recently-added sort works from the warm-launch cache too. Additive
         * ALTER on an existing table; old rows default to 0 (unknown), which
         * sorts last.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `local_songs` ADD COLUMN `dateAdded` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * The SSH/SFTP library index. Two new tables and nothing touched, so
         * additive again - and a real migration rather than a destructive
         * fallback for the same reason as the others: v3 holds favourites,
         * playlists and provider accounts that cannot be re-derived. The index
         * itself could be, but dropping the whole database to rebuild it would
         * take everything else with it.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sftp_tracks` (" +
                        "`accountId` TEXT NOT NULL, `path` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, `album` TEXT NOT NULL, `albumKey` TEXT NOT NULL, " +
                        "`artistKey` TEXT NOT NULL, `track` INTEGER NOT NULL, `year` INTEGER NOT NULL, " +
                        "`size` INTEGER NOT NULL, `mtime` INTEGER NOT NULL, `ext` TEXT NOT NULL, " +
                        "`scanId` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `path`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sftp_tracks_accountId_albumKey` " +
                        "ON `sftp_tracks` (`accountId`, `albumKey`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sftp_tracks_accountId_artistKey` " +
                        "ON `sftp_tracks` (`accountId`, `artistKey`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sftp_tracks_accountId_scanId` " +
                        "ON `sftp_tracks` (`accountId`, `scanId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sftp_index` (" +
                        "`accountId` TEXT NOT NULL, `folders` TEXT NOT NULL, " +
                        "`scannedAt` INTEGER NOT NULL, `tracks` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`accountId`))"
                )
            }
        }

        /**
         * The directory snapshots and write-through feed cache. Two new
         * tables and nothing touched, so additive again - the same reasoning
         * as the podcast migration, but this one rides on top of v4.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `kv_cache` (" +
                        "`key` TEXT NOT NULL, `json` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`key`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcast_feed_cache` (" +
                        "`feedUrl` TEXT NOT NULL, `showJson` TEXT NOT NULL, " +
                        "`episodesJson` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`feedUrl`))"
                )
            }
        }

        /**
         * Play counts. One new table and nothing touched, additive like the
         * ones before it - stats are re-derivable (replays recount), so even
         * a destructive fallback would only lose counts, but a real migration
         * keeps them for free.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `play_stats` (" +
                        "`url` TEXT NOT NULL, `plays` INTEGER NOT NULL, " +
                        "`lastPlayedAt` INTEGER NOT NULL, PRIMARY KEY(`url`))"
                )
            }
        }

        fun get(context: Context): CliampDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CliampDatabase::class.java,
                "cliamp.db",
            )
                // playlist_members cascades from playlists, which only works
                // with foreign keys actually switched on
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                .also { instance = it }
        }
    }
}
