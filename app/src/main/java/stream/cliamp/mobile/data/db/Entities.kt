package stream.cliamp.mobile.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource

/**
 * A station, however it was obtained. Radio, a local file and a provider track
 * all reach the player as a Station, so they all persist through one table
 * rather than three near-identical ones.
 *
 * The URL is the key. It is what identifies a station across the directory,
 * favourites and history, and it is what the player is ultimately handed. For
 * provider tracks it is the opaque cliamp-provider:// reference, never a signed
 * URL, so nothing replayable is written to disk.
 */
@Entity(tableName = "stations")
data class StationEntity(
    @PrimaryKey val url: String,
    val stationId: String,
    val name: String,
    val source: String,
    val slug: String = "",
    val tags: String = "",
    val country: String = "",
    val countryCode: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val votes: Int = 0,
    val homepage: String = "",
    val favicon: String = "",
    val uuid: String = "",
    val cover: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
) {
    fun toStation() = Station(
        id = stationId, name = name, url = url,
        source = runCatching { StationSource.valueOf(source) }.getOrDefault(StationSource.Custom),
        slug = slug, tags = tags, country = country, countryCode = countryCode,
        codec = codec, bitrate = bitrate, votes = votes, homepage = homepage,
        favicon = favicon, uuid = uuid, cover = cover, artist = artist,
        album = album, durationMs = durationMs,
    )
}

fun Station.toEntity() = StationEntity(
    url = url, stationId = id, name = name, source = source.name, slug = slug,
    tags = tags, country = country, countryCode = countryCode, codec = codec,
    bitrate = bitrate, votes = votes, homepage = homepage, favicon = favicon,
    uuid = uuid, cover = cover, artist = artist, album = album, durationMs = durationMs,
)

/**
 * Membership tables carry only a URL and an ordinal. The station's own fields
 * live once in [StationEntity], so renaming or re-tagging a station does not
 * have to be chased through every list it appears in.
 */
@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val url: String, val position: Int)

@Entity(tableName = "history", indices = [Index("playedAt")])
data class HistoryEntity(@PrimaryKey val url: String, val playedAt: Long)

@Entity(tableName = "custom_stations")
data class CustomStationEntity(@PrimaryKey val url: String, val position: Int)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val slug: String,
    val name: String,
    val cover: String = "",
    val pinned: Boolean = false,
    val position: Int = 0,
)

/**
 * Deleting a playlist takes its members with it, which the three hand-synced
 * JSON blobs this replaces had to remember to do by hand.
 */
@Entity(
    tableName = "playlist_members",
    primaryKeys = ["slug", "songId"],
    indices = [Index("slug")],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["slug"],
            childColumns = ["slug"],
            onDelete = androidx.room.ForeignKey.CASCADE,
        )
    ],
)
data class PlaylistMemberEntity(val slug: String, val songId: String, val position: Int)

/** The device's audio, cached so the library survives a cold start. */
@Entity(tableName = "local_songs", indices = [Index("sortKey")])
data class LocalSongEntity(
    @PrimaryKey val songId: String,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: String,
    val cover: String,
    val sortKey: String,
)

/** Provider accounts. Secret fields are encrypted before they reach this table. */
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val providerKey: String,
    val label: String,
    val valuesJson: String,
)
