package stream.kleeamp.mobile.servers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import stream.kleeamp.mobile.playback.ResolvedStream

class ProviderRouterTest {
    private class FakeMediaProvider(
        override val key: String,
        private val url: String,
    ) : MediaProvider {
        override suspend fun stream(account: ProviderAccount, trackId: String) = ResolvedStream(url)
    }

    private fun account(key: String) = ProviderAccount(
        id = "account-1",
        providerKey = key,
        label = "test",
        values = mapOf("url" to "http://example.invalid"),
    )

    private val router = ProviderRouter(
        listOf(
            FakeMediaProvider("ssh", "sftp://account-1/some/track"),
            FakeMediaProvider("subsonic", "http://example.invalid/stream"),
        )
    )

    @Test
    fun sshRoutesToSftpUri() = runTest {
        assertEquals(
            "sftp://account-1/some/track",
            router.stream(account("ssh"), "some/track").url,
        )
    }

    @Test
    fun subsonicRoutesToHttp() = runTest {
        assertEquals(
            "http://example.invalid/stream",
            router.stream(account("subsonic"), "42").url,
        )
    }

    @Test
    fun unknownKeyFallsBackToSubsonic() {
        assertSame(SubsonicMediaProvider, router.providerFor("nope"))
    }

    @Test
    fun defaultsCoverEveryKnownKey() {
        val defaults = ProviderRouter(ProviderRouter.defaults())
        for (key in listOf("ssh", "jellyfin", "emby", "plex", "abs", "lyrion", "subsonic")) {
            val provider = defaults.providerFor(key)
            assertEquals(
                when (key) {
                    "emby" -> "jellyfin"
                    else -> key
                },
                provider.key,
            )
        }
    }
}
