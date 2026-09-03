package stream.cliamp.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    ],
    version = 1,
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

    companion object {
        @Volatile private var instance: CliampDatabase? = null

        fun get(context: Context): CliampDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CliampDatabase::class.java,
                "cliamp.db",
            )
                // playlist_members cascades from playlists, which only works
                // with foreign keys actually switched on
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                .also { instance = it }
        }
    }
}
