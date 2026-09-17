package stream.kleeamp.mobile.data.provider

/**
 * A declarative description of what a provider needs before it can be used.
 *
 * This is a port of `providerSpec` / `fieldSpec` from cliamp desktop's
 * cmd/setup.go. Keeping it declarative rather than writing a screen per
 * provider is what makes sixteen of them tractable: the wizard renders whatever
 * the spec lists, and adding a provider is a data change.
 */
/**
 * What a successful probe learned. [name] is what the account is called from
 * then on, so it stays "navidrome" rather than drifting every time the server
 * is upgraded; [detail] is the version, shown once as probe feedback to
 * confirm you reached the box you meant.
 *
 * [values] are fields the probe filled in on the form's behalf, merged back
 * into it before saving. SSH needs both halves of that: the host key
 * fingerprint, which only the server can supply and which is pinned from then
 * on, and the music folders, which the probe can go and find rather than
 * making someone recall an absolute path from memory.
 */
data class ProviderIdentity(
    val name: String,
    val detail: String = "",
    val values: Map<String, String> = emptyMap(),
)

data class FieldSpec(
    val key: String,
    val label: String,
    val help: String = "",
    val required: Boolean = true,
    val secret: Boolean = false,
    val default: String = "",
    val keyboard: FieldKeyboard = FieldKeyboard.Text,
    /**
     * Rows to show for fields whose value is not one line: a list of folders,
     * or a pasted private key.
     */
    val lines: Int = 1,
    /**
     * Hides the field unless the predicate holds. Jellyfin needs this: it takes
     * either an API token or a username and password, and a flat form cannot
     * express "one or the other".
     */
    val onlyIf: ((Map<String, String>) -> Boolean)? = null,
)

enum class FieldKeyboard { Text, Url, Number }

/** A fixed set of choices, rendered as chips rather than a text field. */
data class PickerSpec(
    val key: String,
    val label: String,
    val options: List<PickerOption>,
    val default: String,
)

data class PickerOption(val value: String, val label: String)

data class ProviderSpec(
    val key: String,
    val name: String,
    /** Pre-form blurb. Says what the thing is and where the docs are. */
    val intro: List<String>,
    val fields: List<FieldSpec>,
    val picker: PickerSpec? = null,
    /**
     * Probes the live server. Nothing is saved until this succeeds, so a typo
     * fails here rather than silently at first playback.
     */
    val validate: suspend (Map<String, String>) -> Result<ProviderIdentity>,
    /** Cross-field rules, run before [validate]. Returns an error or null. */
    val extraValidate: ((Map<String, String>) -> String?)? = null,
    /**
     * Whether more than one account of this type makes sense. Every provider
     * allows it: people run two Navidromes or two Jellyfins as easily as two
     * SSH hosts, and accounts are keyed by id throughout.
     */
    val multiple: Boolean = false,
    /** The line under a configured account's name in the providers list. */
    val summary: (Map<String, String>) -> String = { it["url"].orEmpty() },
) {
    /** Fields currently applicable, honouring every [FieldSpec.onlyIf]. */
    fun visibleFields(values: Map<String, String>): List<FieldSpec> =
        fields.filter { it.onlyIf?.invoke(values) ?: true }

    fun missingRequired(values: Map<String, String>): List<FieldSpec> =
        visibleFields(values).filter { it.required && values[it.key].isNullOrBlank() }
}

/** A configured provider: which spec, plus the values the user supplied. */
data class ProviderAccount(
    val id: String,
    val providerKey: String,
    val label: String,
    val values: Map<String, String>,
) {
    val url: String get() = values["url"].orEmpty()
}
