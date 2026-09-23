package stream.kleeamp.mobile.servers

/**
 * Every provider the app can add. Adding one is a data change here plus a
 * client; the wizard and the list screen need no edits.
 */
object ProviderCatalog {

    val navidrome = ProviderSpec(
        key = "navidrome",
        name = "Navidrome",
        intro = listOf(
            "self-hosted music server speaking the subsonic api.",
            "login with username and password.",
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
        multiple = true,
        validate = { v ->
            SubsonicClient(v["url"].orEmpty(), v["user"].orEmpty(), v["password"].orEmpty()).ping()
        },
    )

    val subsonic = ProviderSpec(
        key = "subsonic",
        name = "Subsonic",
        intro = listOf(
            "generic subsonic api server.",
            "works with gonic, airsonic, supersonic and other subsonic servers.",
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
        multiple = true,
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
        multiple = true,
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
        multiple = true,
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
        multiple = true,
        validate = { v ->
            PlexClient(v["url"].orEmpty(), v["token"].orEmpty()).ping()
        },
    )

    val abs = ProviderSpec(
        key = "abs",
        name = "Audiobookshelf",
        intro = listOf(
            "self-hosted audiobook and podcast server.",
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
                help = "e.g. abs.example.com — https is assumed",
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
        multiple = true,
        validate = { v ->
            AudiobookshelfClient(
                v["url"].orEmpty(),
                v["token"].orEmpty(),
                v["user"].orEmpty(),
                v["password"].orEmpty(),
            ).ping()
        },
    )

    val lyrion = ProviderSpec(
        key = "lyrion",
        name = "Lyrion",
        intro = listOf(
            "squeezebox / logitech media server with a json-rpc api.",
            "username and password only if the server asks for them.",
        ),
        fields = listOf(
            FieldSpec(
                key = "url",
                label = "Server URL",
                help = "e.g. squeezebox.example.com:9000 — https is assumed",
                keyboard = FieldKeyboard.Url,
            ),
            FieldSpec(key = "user", label = "Username", required = false),
            FieldSpec(key = "password", label = "Password", secret = true, required = false),
        ),
        multiple = true,
        validate = { v ->
            LyrionClient(
                v["url"].orEmpty(),
                v["user"].orEmpty(),
                v["password"].orEmpty(),
            ).ping()
        },
    )

    /**
     * Not a music server at all: a machine with music on it.
     *
     * Everything else here answers an API that already knows what an album is.
     * This one gets a filesystem, so the layout is the metadata and the folders
     * have to be named up front - or found, if the field is left empty.
     */
    val ssh = ProviderSpec(
        key = "ssh",
        name = "SSH / SFTP",
        intro = listOf(
            "any box you can ssh into: a nas, a pi, a seedbox, a desktop.",
            "tailscale ssh needs no credentials - the tailnet is the login.",
            "name the music folders; tracks stream over sftp, nothing is downloaded.",
        ),
        picker = PickerSpec(
            key = "_auth",
            label = "sign in with",
            options = listOf(
                PickerOption("password", "Password"),
                PickerOption("key", "Private Key"),
                PickerOption("none", "Tailscale"),
            ),
            default = "password",
        ),
        fields = listOf(
            FieldSpec(
                key = "host",
                label = "Host",
                help = "nas.local, 10.0.0.4, or a tailscale name",
                keyboard = FieldKeyboard.Url,
            ),
            FieldSpec(
                key = "port",
                label = "Port",
                help = "22",
                required = false,
                default = "22",
                keyboard = FieldKeyboard.Number,
            ),
            FieldSpec(key = "user", label = "Username"),
            FieldSpec(
                key = "password",
                label = "Password",
                secret = true,
                onlyIf = { it["_auth"] == "password" },
            ),
            FieldSpec(
                key = "key",
                label = "Private Key",
                help = "paste the whole -----BEGIN ... PRIVATE KEY----- block",
                secret = true,
                lines = 4,
                onlyIf = { it["_auth"] == "key" },
            ),
            FieldSpec(
                key = "passphrase",
                label = "Key Passphrase",
                secret = true,
                required = false,
                onlyIf = { it["_auth"] == "key" },
            ),
            FieldSpec(
                key = "folders",
                label = "Music Folders",
                help = "one per line - leave empty and the probe goes looking",
                required = false,
                lines = 3,
            ),
        ),
        multiple = true,
        summary = { v ->
            val cfg = sshConfig(v)
            val folders = cfg.folders.size
            listOf(
                cfg.where,
                when (folders) {
                    0 -> ""
                    1 -> cfg.folders.first()
                    else -> "$folders folders"
                },
            ).filter { it.isNotBlank() }.joinToString(" · ")
        },
        validate = { v -> probeSsh(v) },
    )

    val all: List<ProviderSpec> = listOf(navidrome, subsonic, jellyfin, emby, plex, abs, lyrion, ssh)

    fun byKey(key: String): ProviderSpec? = all.firstOrNull { it.key == key }
}
