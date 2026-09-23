package stream.kleeamp.mobile.servers

/** Builds the API client a configured account represents. */
fun ProviderAccount.subsonic(): SubsonicClient = SubsonicClient(
    values["url"].orEmpty(),
    values["user"].orEmpty(),
    values["password"].orEmpty(),
)

fun ProviderAccount.jellyfin(): JellyfinClient = JellyfinClient(
    values["url"].orEmpty(),
    values["token"].orEmpty(),
    values["user"].orEmpty(),
    values["password"].orEmpty(),
    providerKey,
)

fun ProviderAccount.plex(): PlexClient = PlexClient(
    values["url"].orEmpty(),
    values["token"].orEmpty(),
)

fun ProviderAccount.lyrion(): LyrionClient = LyrionClient(
    values["url"].orEmpty(),
    values["user"].orEmpty(),
    values["password"].orEmpty(),
)

fun ProviderAccount.audiobookshelf(): AudiobookshelfClient = AudiobookshelfClient(
    values["url"].orEmpty(),
    values["token"].orEmpty(),
    values["user"].orEmpty(),
    values["password"].orEmpty(),
)