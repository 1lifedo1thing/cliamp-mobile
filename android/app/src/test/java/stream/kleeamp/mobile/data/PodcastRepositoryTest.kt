package stream.kleeamp.mobile.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import stream.kleeamp.mobile.data.db.CacheDao
import stream.kleeamp.mobile.data.db.EpisodeProgressEntity
import stream.kleeamp.mobile.data.db.KvCacheEntity
import stream.kleeamp.mobile.data.db.PodcastDao
import stream.kleeamp.mobile.data.db.PodcastFeedCacheEntity
import stream.kleeamp.mobile.data.db.PodcastSubscriptionEntity
import stream.kleeamp.mobile.net.Http

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastRepositoryTest {
    private val showA = PodcastShow("a", "Show A", "https://example.com/a.xml")
    private val showB = PodcastShow("b", "Show B", "https://example.com/b.xml")

    @Test
    fun lateSuccessCannotReplaceTheNewerShow() = runTest {
        val oldResponse = CompletableDeferred<Result<PodcastFeed.Loaded>>()
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), FakeCache()) { show ->
            if (show == showA) withContext(NonCancellable) { oldResponse.await() }
            else Result.success(loaded(showB))
        }
        repository.openShow(showA)
        runCurrent()
        repository.openShow(showB)
        runCurrent()
        assertEquals(loadedState(showB), repository.show.value)

        oldResponse.complete(Result.success(loaded(showA)))
        runCurrent()
        assertEquals(loadedState(showB), repository.show.value)
    }

    @Test
    fun lateFailureCannotReplaceOrStopTheNewerLoadingShow() = runTest {
        val oldResponse = CompletableDeferred<Result<PodcastFeed.Loaded>>()
        val newResponse = CompletableDeferred<Result<PodcastFeed.Loaded>>()
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), FakeCache()) { show ->
            withContext(NonCancellable) {
                if (show == showA) oldResponse.await() else newResponse.await()
            }
        }
        repository.openShow(showA)
        runCurrent()
        repository.openShow(showB)
        runCurrent()
        oldResponse.complete(Result.failure(IOException("old feed failed")))
        runCurrent()
        val stateWhileBLoads = repository.show.value
        newResponse.complete(Result.success(loaded(showB)))
        runCurrent()
        assertEquals(ShowState(show = showB, loading = true), stateWhileBLoads)
        assertEquals(loadedState(showB), repository.show.value)
    }

    @Test
    fun returningToTheSameFeedRejectsItsEarlierResponse() = runTest {
        val oldResponse = CompletableDeferred<Result<PodcastFeed.Loaded>>()
        var callsToA = 0
        val fresh = loaded(showA, "fresh")
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), FakeCache()) { show ->
            if (show == showA && ++callsToA == 1) {
                withContext(NonCancellable) { oldResponse.await() }
            } else Result.success(if (show == showA) fresh else loaded(showB))
        }
        repository.openShow(showA)
        runCurrent()
        repository.openShow(showB)
        runCurrent()
        repository.openShow(showA)
        runCurrent()
        oldResponse.complete(Result.success(loaded(showA, "stale")))
        runCurrent()
        assertEquals(ShowState(show = showA, episodes = fresh.episodes), repository.show.value)
    }

    @Test
    fun openingAnotherShowCancelsThePreviousLoad() = runTest {
        val waiting = CompletableDeferred<Unit>()
        var cancelled = false
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), FakeCache()) { show ->
            if (show == showA) {
                try {
                    waiting.await()
                } finally {
                    cancelled = true
                }
            }
            Result.success(loaded(show))
        }
        repository.openShow(showA)
        runCurrent()
        repository.openShow(showB)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(loadedState(showB), repository.show.value)
    }

    @Test
    fun lateCacheReadCannotPopulateAnotherShow() = runTest {
        val oldCache = CompletableDeferred<PodcastFeedCacheEntity?>()
        val newResponse = CompletableDeferred<Result<PodcastFeed.Loaded>>()
        val cache = FakeCache().apply {
            readFeed = { url ->
                if (url == showA.feedUrl) withContext(NonCancellable) { oldCache.await() } else null
            }
        }
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), cache) { show ->
            if (show == showB) newResponse.await() else Result.success(loaded(showA))
        }
        repository.openShow(showA)
        runCurrent()
        repository.openShow(showB)
        runCurrent()
        oldCache.complete(cached(showA))
        runCurrent()
        val stateWhileBLoads = repository.show.value
        newResponse.complete(Result.success(loaded(showB)))
        runCurrent()
        assertEquals(ShowState(show = showB, loading = true), stateWhileBLoads)
        assertEquals(loadedState(showB), repository.show.value)
    }

    @Test
    fun currentFeedStillShowsCachedEpisodesWhenNetworkFails() = runTest {
        val response = CompletableDeferred<Result<PodcastFeed.Loaded>>()
        val cache = FakeCache().apply { readFeed = { cached(showA) } }
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), cache) { response.await() }
        repository.openShow(showA)
        runCurrent()
        assertEquals(loadedState(showA).copy(loading = true), repository.show.value)

        response.complete(Result.failure(IOException("offline")))
        runCurrent()
        assertEquals(loadedState(showA), repository.show.value)
    }

    @Test
    fun currentFeedFailureWithoutCacheIsStillReported() = runTest {
        val repository = PodcastRepository(backgroundScope, FakePodcasts(), FakeCache()) {
            Result.failure(IOException("offline"))
        }
        repository.openShow(showA)
        runCurrent()
        assertEquals("offline", repository.show.value.error)
        assertEquals(showA, repository.show.value.show)
        assertFalse(repository.show.value.loading)
    }

    private fun loaded(show: PodcastShow, suffix: String = "episode") = PodcastFeed.Loaded(
        show,
        listOf(PodcastEpisode("${show.id}:$suffix", suffix, "https://example.com/${show.id}/$suffix.mp3")),
    )

    private fun loadedState(show: PodcastShow) = ShowState(show, loaded(show).episodes)

    private fun cached(show: PodcastShow) = PodcastFeedCacheEntity(
        feedUrl = show.feedUrl,
        showJson = Http.json.encodeToString(show),
        episodesJson = Http.json.encodeToString(loaded(show).episodes),
        savedAt = System.currentTimeMillis(),
    )

    private class FakeCache : CacheDao {
        var readFeed: suspend (String) -> PodcastFeedCacheEntity? = { null }
        override suspend fun getFeed(feedUrl: String) = readFeed(feedUrl)
        override suspend fun putFeed(row: PodcastFeedCacheEntity) = Unit
        override suspend fun get(key: String): KvCacheEntity? = null
        override suspend fun put(row: KvCacheEntity) = Unit
    }

    private class FakePodcasts : PodcastDao {
        override fun subscriptions() = flowOf(emptyList<PodcastSubscriptionEntity>())
        override fun allProgress() = flowOf(emptyList<EpisodeProgressEntity>())
        override suspend fun isSubscribed(feedUrl: String) = false
        override suspend fun nextTopPosition() = 0
        override suspend fun subscribe(row: PodcastSubscriptionEntity) = Unit
        override suspend fun unsubscribe(feedUrl: String) = Unit
        override suspend fun progress(url: String): EpisodeProgressEntity? = null
        override suspend fun saveProgress(row: EpisodeProgressEntity) = Unit
        override suspend fun clearProgress(url: String) = Unit
    }
}
