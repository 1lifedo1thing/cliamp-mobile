package stream.kleeamp.mobile.servers

import stream.kleeamp.mobile.playback.ResolvedStream

/**
 * Picks the [MediaProvider] for an account's [ProviderAccount.providerKey].
 * Unknown keys fall back to Subsonic, exactly like the inline `when` this
 * replaces, so stored accounts from any version keep resolving.
 */
class ProviderRouter(
    providers: List<MediaProvider> = defaults(),
    private val aliases: Map<String, String> = mapOf("emby" to JellyfinMediaProvider.key),
    private val fallback: MediaProvider = SubsonicMediaProvider,
) {
    private val byKey: Map<String, MediaProvider> = providers.associateBy { it.key }

    fun providerFor(accountKey: String): MediaProvider =
        byKey[accountKey] ?: aliases[accountKey]?.let(byKey::get) ?: fallback

    suspend fun stream(account: ProviderAccount, trackId: String): ResolvedStream =
        providerFor(account.providerKey).stream(account, trackId)

    companion object {
        fun defaults(): List<MediaProvider> = listOf(
            SshMediaProvider,
            JellyfinMediaProvider,
            PlexMediaProvider,
            AudiobookshelfMediaProvider,
            LyrionMediaProvider,
            SubsonicMediaProvider,
        )
    }
}
