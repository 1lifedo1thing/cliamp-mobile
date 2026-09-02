package stream.cliamp.mobile.data.provider

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