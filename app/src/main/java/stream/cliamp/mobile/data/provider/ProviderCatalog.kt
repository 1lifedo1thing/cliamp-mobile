package stream.cliamp.mobile.data.provider

/**
 * Every provider the app can add. Adding one is a data change here plus a
 * client; the wizard and the list screen need no edits.
 */
object ProviderCatalog {

    val navidrome = ProviderSpec(
        key = "navidrome",
        name = "Navidrome / Subsonic",
        intro = listOf(
            "self-hosted music server speaking the subsonic api.",
            "also works with gonic, airsonic and other subsonic servers.",
        ),
        fields = listOf(
            FieldSpec(
                key = "url",
                label = "Server URL",
                help = "e.g. music.example.com — https is assumed",
                keyboard = FieldKeyboard.Url,
            ),
            FieldSpec(key = "user", label = "Username"),
            FieldSpec(key = "password", label = "Password", secret = true),
        ),
        validate = { v ->
            SubsonicClient(v["url"].orEmpty(), v["user"].orEmpty(), v["password"].orEmpty()).ping()
        },
    )

    val all: List<ProviderSpec> = listOf(navidrome)

    fun byKey(key: String): ProviderSpec? = all.firstOrNull { it.key == key }

    /**
     * Named here rather than left implicit so the picker can say what is coming
     * without pretending it works yet.
     */
    val planned = listOf("Jellyfin", "Emby", "Plex", "Audiobookshelf", "Lyrion")
}
