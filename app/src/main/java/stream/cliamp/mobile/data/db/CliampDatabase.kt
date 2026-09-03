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
    ],
    version = 2,
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

        fun get(context: Context): CliampDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CliampDatabase::class.java,
                "cliamp.db",
            )
                // playlist_members cascades from playlists, which only works
                // with foreign keys actually switched on
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
