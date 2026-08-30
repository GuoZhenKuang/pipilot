package top.guozk.pipilot.coresessions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionIndexRepositoryTest {
    @Test
    fun `initialize serves cached sessions then refreshes with merged remote`() =
        runTest {
            val hostId = "host-a"
            val dispatcher = StandardTestDispatcher(testScheduler)
            val unchangedCachedSession = buildUnchangedSession()
            val changedCachedSession = buildChangedSession()

            val cache = InMemorySessionIndexCache()
            cache.write(
                CachedSessionIndex(
                    hostId = hostId,
                    cachedAtEpochMs = 100,
                    groups =
                        listOf(
                            SessionGroup(
                                cwd = "/tmp/project",
                                sessions = listOf(unchangedCachedSession, changedCachedSession),
                            ),
                        ),
                ),
            )

            val remote = FakeSessionRemoteDataSource()
            remote.groupsByHost[hostId] =
                listOf(
                    SessionGroup(
                        cwd = "/tmp/project",
                        sessions =
                            listOf(
                                unchangedCachedSession,
                                changedCachedSession.copy(firstUserMessagePreview = "modernized"),
                            ),
                    ),
                )

            val repository = createRepository(remote = remote, cache = cache, dispatcher = dispatcher)
            repository.initialize(hostId)

            assertCachedState(repository = repository, hostId = hostId)

            advanceUntilIdle()

            val refreshedState =
                repository.observe(hostId).first { state ->
                    state.source == SessionIndexSource.REMOTE && !state.isRefreshing
                }

            val refreshedSessions = refreshedState.groups.single().sessions
            assertEquals(2, refreshedSessions.size)

            val unchangedRef = refreshedSessions.first { session -> session.sessionPath == "/tmp/a.jsonl" }
            val changedRef = refreshedSessions.first { session -> session.sessionPath == "/tmp/b.jsonl" }

            assertTrue(unchangedRef === unchangedCachedSession)
            assertEquals("modernized", changedRef.firstUserMessagePreview)

            assertFilteredPaymentState(repository = repository, hostId = hostId)
        }

    @Test
    fun `all host initialization serves every cache before bounded refresh and isolates failure`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val cache = InMemorySessionIndexCache()
            (1..4).forEach { index ->
                cache.write(
                    CachedSessionIndex(
                        hostId = "host-$index",
                        cachedAtEpochMs = index.toLong(),
                        groups = listOf(SessionGroup("/private/$index", listOf(buildUnchangedSession()))),
                    ),
                )
            }
            val remote =
                FakeSessionRemoteDataSource().apply {
                    fetchDelayMs = 100
                    failingHosts += "host-4"
                }
            val repository = createRepository(remote, cache, dispatcher)
            val initialize = async { repository.initializeAll((1..4).map { "host-$it" }, maxConcurrentRefreshes = 2) }

            runCurrent()
            val cached = repository.observeAll((1..4).map { "host-$it" }).first()
            assertTrue(cached.all { it.source == SessionIndexSource.CACHE })
            assertTrue(remote.maximumConcurrentFetches <= 2)

            advanceUntilIdle()
            initialize.await()
            val completed = repository.observeAll((1..4).map { "host-$it" }).first()
            assertEquals("sanitized failure", completed.first { it.hostId == "host-4" }.errorMessage)
            assertTrue(completed.first { it.hostId == "host-4" }.groups.isNotEmpty())
            assertEquals(SessionIndexSource.REMOTE, completed.first { it.hostId == "host-1" }.source)
            assertEquals(2, remote.maximumConcurrentFetches)
        }

    @Test
    fun `privacy safe repository query never matches path or cwd`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val remote =
                FakeSessionRemoteDataSource().apply {
                    groupsByHost["host-a"] =
                        listOf(
                            SessionGroup(
                                "/private/payment-workspace",
                                listOf(
                                    buildUnchangedSession().copy(
                                        sessionPath = "/private/path-only.jsonl",
                                        firstUserMessagePreview = "hello",
                                    ),
                                ),
                            ),
                        )
                }
            val repository = createRepository(remote, InMemorySessionIndexCache(), dispatcher)
            repository.refresh("host-a")

            assertTrue(repository.observe("host-a", "/private/path-only").first().groups.isEmpty())
            assertTrue(repository.observe("host-a", "payment-workspace").first().groups.isEmpty())
            assertFalse(repository.observe("host-a", "hello").first().groups.isEmpty())
        }

    @Test
    fun `concurrent refreshes share one remote fetch`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val remote = FakeSessionRemoteDataSource().apply { fetchDelayMs = 10 }
            remote.groupsByHost["host-a"] = emptyList()
            val repository = createRepository(remote, InMemorySessionIndexCache(), dispatcher)

            val first = async { repository.refresh("host-a") }
            val second = async { repository.refresh("host-a") }
            advanceUntilIdle()

            first.await()
            second.await()
            assertEquals(1, remote.fetchCount)
        }

    @Test
    fun `refresh interval prevents redundant remote fetches`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val remote = FakeSessionRemoteDataSource()
            val repository = createRepository(remote, InMemorySessionIndexCache(), dispatcher)

            repository.refresh("host-a")
            repository.refresh("host-a")

            assertEquals(1, remote.fetchCount)
        }

    @Test
    fun `failed refresh applies bounded retry backoff`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var now = 1_000L
            val remote = FakeSessionRemoteDataSource().apply { failingHosts += "host-a" }
            val repository =
                SessionIndexRepository(
                    remoteDataSource = remote,
                    cache = InMemorySessionIndexCache(),
                    scope = CoroutineScope(dispatcher),
                    nowEpochMs = { now },
                    minimumRefreshIntervalMs = 1_000L,
                    maximumRefreshBackoffMs = 8_000L,
                )

            repository.refresh("host-a")
            now = 2_500L
            repository.refresh("host-a")
            assertEquals(1, remote.fetchCount)

            now = 3_000L
            repository.refresh("host-a")
            assertEquals(2, remote.fetchCount)
        }

    @Test
    fun `file cache persists entries per host`() =
        runTest {
            val directory = Files.createTempDirectory("session-cache-test")
            val cache = FileSessionIndexCache(cacheDirectory = directory)
            val index =
                CachedSessionIndex(
                    hostId = "host-b",
                    cachedAtEpochMs = 321,
                    groups =
                        listOf(
                            SessionGroup(
                                cwd = "/tmp/project-b",
                                sessions =
                                    listOf(
                                        SessionRecord(
                                            sessionPath = "/tmp/session.jsonl",
                                            cwd = "/tmp/project-b",
                                            createdAt = "2026-01-01T00:00:00.000Z",
                                            updatedAt = "2026-01-01T01:00:00.000Z",
                                        ),
                                    ),
                            ),
                        ),
                )

            cache.write(index)
            val loaded = cache.read("host-b")

            assertNotNull(loaded)
            assertEquals(index, loaded)
        }

    private suspend fun assertCachedState(
        repository: SessionIndexRepository,
        hostId: String,
    ) {
        val cachedState = repository.observe(hostId).first { state -> state.source == SessionIndexSource.CACHE }
        assertEquals(2, cachedState.groups.single().sessions.size)
        assertEquals(100, cachedState.lastUpdatedEpochMs)
    }

    private suspend fun assertFilteredPaymentState(
        repository: SessionIndexRepository,
        hostId: String,
    ) {
        val filtered =
            repository.observe(hostId, query = "payment").first { state ->
                state.source == SessionIndexSource.REMOTE
            }
        assertEquals(1, filtered.groups.single().sessions.size)
        assertEquals("/tmp/a.jsonl", filtered.groups.single().sessions.single().sessionPath)
    }

    private fun buildUnchangedSession(): SessionRecord {
        return SessionRecord(
            sessionPath = "/tmp/a.jsonl",
            cwd = "/tmp/project",
            createdAt = "2026-01-01T10:00:00.000Z",
            updatedAt = "2026-01-02T10:00:00.000Z",
            displayName = "Alpha",
            firstUserMessagePreview = "payment flow",
            messageCount = 3,
            lastModel = "claude",
        )
    }

    private fun buildChangedSession(): SessionRecord {
        return SessionRecord(
            sessionPath = "/tmp/b.jsonl",
            cwd = "/tmp/project",
            createdAt = "2026-01-01T10:00:00.000Z",
            updatedAt = "2026-01-03T10:00:00.000Z",
            displayName = "Beta",
            firstUserMessagePreview = "legacy",
            messageCount = 7,
            lastModel = "gpt",
        )
    }

    private fun createRepository(
        remote: SessionIndexRemoteDataSource,
        cache: SessionIndexCache,
        dispatcher: TestDispatcher,
    ): SessionIndexRepository {
        val repositoryScope = CoroutineScope(dispatcher)

        return SessionIndexRepository(
            remoteDataSource = remote,
            cache = cache,
            scope = repositoryScope,
            nowEpochMs = { 999 },
        )
    }

    private class FakeSessionRemoteDataSource : SessionIndexRemoteDataSource {
        val groupsByHost = linkedMapOf<String, List<SessionGroup>>()
        val failingHosts = mutableSetOf<String>()
        var fetchCount: Int = 0
        var fetchDelayMs: Long = 0
        var concurrentFetches: Int = 0
        var maximumConcurrentFetches: Int = 0

        override suspend fun fetch(hostId: String): List<SessionGroup> {
            fetchCount += 1
            concurrentFetches += 1
            maximumConcurrentFetches = maxOf(maximumConcurrentFetches, concurrentFetches)
            return try {
                if (fetchDelayMs > 0) delay(fetchDelayMs)
                if (hostId in failingHosts) error("sanitized failure")
                groupsByHost[hostId] ?: emptyList()
            } finally {
                concurrentFetches -= 1
            }
        }
    }
}
