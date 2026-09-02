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

    val jellyfin = ProviderSpec(
        key = "jellyfin",
        name = "Jellyfin",
        intro = listOf(
            "self-hosted media server.",
            "api token, or a username and password.",
        ),
        picker = PickerSpec(
            key = "_auth",
            label = "sign in with",
            options = listOf(
                PickerOption("token", "API Token"),
                PickerOption("password", "Username & Password"),
            ),
            default = "token",
        ),
        fields = listOf(
            FieldSpec(
                key = "url",
                label = "Server URL",
                help = "e.g. media.example.com — https is assumed",
                keyboard = FieldKeyboard.Url,
            ),
            FieldSpec(
                key = "token",
                label = "API Token",
                secret = true,
                onlyIf = { it["_auth"] == "token" },
            ),
            FieldSpec(
                key = "user",
                label = "Username",
                onlyIf = { it["_auth"] == "password" },
            ),
            FieldSpec(
                key = "password",
                label = "Password",
                secret = true,
                onlyIf = { it["_auth"] == "password" },
            ),
        ),
        extraValidate = { v ->
            when (v["_auth"]) {
                "password" -> if (v["user"].isNullOrBlank()) "username is required" else null
                else -> if (v["token"].isNullOrBlank()) "api token is required" else null
            }
        },
        validate = { v ->
            JellyfinClient(
                v["url"].orEmpty(),
                v["token"].orEmpty(),
                v["user"].orEmpty(),
                v["password"].orEmpty(),
                "jellyfin",
            ).ping()
        },
    )

    val emby = ProviderSpec(
        key = "emby",
        name = "Emby",
        intro = listOf(
            "media server; the jellyfin client handles it.",
            "api key, or a username and password.",
        ),
        picker = PickerSpec(
            key = "_auth",
            label = "sign in with",
            options = listOf(
                PickerOption("token", "API Key"),
                PickerOption("password", "Username & Password"),
            ),
            default = "token",
        ),
        fields = listOf(
            FieldSpec(
                key = "url",
                label = "Server URL",
                help = "e.g. media.example.com — https is assumed",
                keyboard = FieldKeyboard.Url,
            ),
            FieldSpec(
                key = "token",
                label = "API Key",
                secret = true,
                onlyIf = { it["_auth"] == "token" },
            ),
            FieldSpec(
                key = "user",
                label = "Username",
                onlyIf = { it["_auth"] == "password" },
            ),
            FieldSpec(
                key = "password",
                label = "Password",
                secret = true,
                onlyIf = { it["_auth"] == "password" },
            ),
        ),
        extraValidate = { v ->
            when (v["_auth"]) {
                "password" -> if (v["user"].isNullOrBlank()) "username is required" else null
                else -> if (v["token"].isNullOrBlank()) "api key is required" else null
            }
        },
        validate = { v ->
            JellyfinClient(
                v["url"].orEmpty(),
                v["token"].orEmpty(),
                v["user"].orEmpty(),
                v["password"].orEmpty(),
                "emby",
            ).ping()
        },
    )

    val plex = ProviderSpec(
        key = "plex",
        name = "Plex",
        intro = listOf(
            "media server; needs a plex token from the app settings.",
            "libraries are read over the plex api.",
        ),
        fields = listOf(
            FieldSpec(
                key = "url",
                label = "Server URL",
                help = "e.g. media.example.com:32400 — https is assumed",
                keyboard = FieldKeyboard.Url,
            ),
            FieldSpec(key = "token", label = "X-Plex-Token", secret = true),
        ),
        validate = { v ->
            PlexClient(v["url"].orEmpty(), v["token"].orEmpty()).ping()
        },
    )

    val all: List<ProviderSpec> = listOf(navidrome, jellyfin, emby, plex)

    fun byKey(key: String): ProviderSpec? = all.firstOrNull { it.key == key }

    /**
     * Named here rather than left implicit so the picker can say what is coming
     * without pretending it works yet.
     */
    val planned = listOf("Audiobookshelf", "Lyrion")
}
