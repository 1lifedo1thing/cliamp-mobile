package stream.cliamp.mobile.data.provider

import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.playback.StreamResolver

/**
 * Provider tracks become [Station]s like everything else the player handles, so
 * the queue, the notification and the widget need no provider-specific paths.
 *
 * The url is deliberately an opaque reference rather than a signed stream URL;
 * see [StreamResolver.providerResolver] for why.
 */
fun SubsonicTrack.toStation(account: ProviderAccount, client: SubsonicClient): Station = Station(
    id = "prov:${account.id}:$id",
    name = title.ifBlank { "untitled" },
    url = "${StreamResolver.PROVIDER_SCHEME}${account.id}/$id",
    source = StationSource.Provider,
    artist = artist,
    album = album,
    durationMs = duration * 1000L,
    cover = coverArt.takeIf { it.isNotBlank() }?.let { client.coverArtUrl(it) }.orEmpty(),
    codec = suffix,
    bitrate = bitRate,
)

fun ProviderAccount.subsonic(): SubsonicClient = SubsonicClient(
    values["url"].orEmpty(),
    values["user"].orEmpty(),
    values["password"].orEmpty(),
)
