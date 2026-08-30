package top.guozk.pipilot.sessions

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.guozk.pipilot.coresessions.InMemorySessionIndexCache
import top.guozk.pipilot.coresessions.SessionGroup
import top.guozk.pipilot.coresessions.SessionIndexRemoteDataSource
import top.guozk.pipilot.coresessions.SessionIndexRepository
import top.guozk.pipilot.coresessions.SessionKey
import top.guozk.pipilot.coresessions.SessionRecord
import top.guozk.pipilot.hosts.HostProfile
import top.guozk.pipilot.hosts.HostProfileStore
import top.guozk.pipilot.hosts.HostTokenStore
import top.guozk.pipilot.testutil.FakeSessionController

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {
    private val alpha = HostProfile("host-a", "Alpha host", "alpha", 8787, false)
    private val beta = HostProfile("host-b", "Beta host", "beta", 8787, false)
    private lateinit var dispatcher: TestDispatcher
    private val viewModels = mutableListOf<SessionsViewModel>()

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `empty profile state is explicit and does not start a session`() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            val viewModel = createViewModel(MutableProfileStore(), FakeRemote(), controller = controller)

            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.hosts.isEmpty())
            assertTrue(viewModel.uiState.value.items.isEmpty())
            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals("请先添加主机，才能浏览会话。", viewModel.uiState.value.errorMessage)
            viewModel.newSession()
            advanceUntilIdle()
            assertEquals(0, controller.ensureConnectedCallCount)
        }

    @Test
    fun `all-host workspace filtering and host switches keep cwd scoped to selected host`() =
        runTest(dispatcher) {
            val profiles = MutableProfileStore(alpha, beta)
            val remote =
                FakeRemote().apply {
                    addRecord(alpha.id, "/work/alpha-space", "alpha-session")
                    addRecord(beta.id, "/work/beta-space", "beta-session")
                }
            val cwdStore =
                InMemorySessionCwdPreferenceStore().apply {
                    setPreferredCwd(alpha.id, "/work/alpha-space")
                    setPreferredCwd(beta.id, "/work/beta-space")
                }
            val controller = FakeSessionController()
            val viewModel = createViewModel(profiles, remote, controller, cwdStore)
            advanceUntilIdle()

            assertEquals(alpha.id, viewModel.uiState.value.selectedHostId)
            assertEquals(listOf("/work/alpha-space"), viewModel.uiState.value.groups.map { it.cwd })

            viewModel.showAllHosts()
            viewModel.onWorkspaceFilterChanged("beta-space")
            assertEquals(alpha.id, viewModel.uiState.value.selectedHostId)
            assertEquals("/work/alpha-space", viewModel.uiState.value.selectedCwd)
            assertEquals(listOf("/work/alpha-space"), viewModel.uiState.value.groups.map { it.cwd })

            viewModel.newSession()
            advanceUntilIdle()
            assertEquals(alpha.id, controller.lastEnsuredHostId)
            assertEquals("/work/alpha-space", controller.lastEnsuredCwd)

            viewModel.onHostSelected(beta.id)
            assertEquals(beta.id, viewModel.uiState.value.selectedHostId)
            assertEquals("/work/beta-space", viewModel.uiState.value.selectedCwd)
            assertEquals(listOf("/work/beta-space"), viewModel.uiState.value.groups.map { it.cwd })

            viewModel.onHostSelected(alpha.id)
            assertEquals("/work/alpha-space", viewModel.uiState.value.selectedCwd)
            assertEquals(listOf("/work/alpha-space"), viewModel.uiState.value.groups.map { it.cwd })
        }

    @Test
    fun `cross-host resume changes the complete host context before New dispatches`() =
        runTest(dispatcher) {
            val profiles = MutableProfileStore(alpha, beta)
            val remote =
                FakeRemote().apply {
                    addRecord(alpha.id, "/work/alpha-space", "alpha-session")
                    addRecord(beta.id, "/work/beta-space", "beta-session")
                }
            val controller = FakeSessionController()
            val viewModel = createViewModel(profiles, remote, controller)
            advanceUntilIdle()

            viewModel.showAllHosts()
            val betaItem = viewModel.uiState.value.items.single { it.hostId == beta.id }
            viewModel.resumeSession(betaItem)

            assertEquals(beta.id, viewModel.uiState.value.selectedHostId)
            assertEquals("/work/beta-space", viewModel.uiState.value.selectedCwd)
            assertEquals(listOf("/work/beta-space"), viewModel.uiState.value.groups.map { it.cwd })
            advanceUntilIdle()

            viewModel.newSession()
            advanceUntilIdle()
            assertEquals(beta.id, controller.lastEnsuredHostId)
            assertEquals("/work/beta-space", controller.lastEnsuredCwd)

            viewModel.onHostSelected(alpha.id)
            viewModel.newSession()
            advanceUntilIdle()
            assertEquals(alpha.id, controller.lastEnsuredHostId)
            assertEquals("/work/alpha-space", controller.lastEnsuredCwd)
        }

    @Test
    fun `profile add endpoint edit and deletion preserve then remove only the local key scope`() =
        runTest(dispatcher) {
            val profiles = MutableProfileStore(alpha)
            val remote =
                FakeRemote().apply {
                    groupsByHost[alpha.id] = listOf(group("/work/alpha", stableRecord("alpha-session", "/work/alpha")))
                    groupsByHost[beta.id] = listOf(group("/work/beta", stableRecord("beta-session", "/work/beta")))
                }
            val savedStorage = InMemorySavedSessionStorage()
            val savedStore = SessionSavedStateStore(savedStorage)
            savedStore.write(SavedSessionsState(pinned = setOf(SessionKey(alpha.id, "alpha-session"))))
            val viewModel = createViewModel(profiles, remote, savedStateStore = savedStore)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.items.single().isPinned)

            profiles.upsert(alpha.copy(host = "alpha-edited"))
            profiles.upsert(beta)
            viewModel.refreshHosts()
            advanceUntilIdle()

            assertEquals(setOf(alpha.id, beta.id), viewModel.uiState.value.hosts.map { it.id }.toSet())
            assertTrue(savedStore.read().pinned.contains(SessionKey(alpha.id, "alpha-session")))

            profiles.delete(alpha.id)
            viewModel.refreshHosts()
            advanceUntilIdle()

            assertEquals(listOf(beta.id), viewModel.uiState.value.hosts.map { it.id })
            assertEquals(beta.id, viewModel.uiState.value.selectedHostId)
            assertTrue(savedStore.read().pinned.isEmpty())
            assertTrue(viewModel.uiState.value.items.none { it.hostId == alpha.id })
        }

    private fun createViewModel(
        profiles: MutableProfileStore,
        remote: FakeRemote,
        controller: FakeSessionController = FakeSessionController(),
        cwdStore: SessionCwdPreferenceStore = InMemorySessionCwdPreferenceStore(),
        savedStateStore: SessionSavedStateStore = SessionSavedStateStore(InMemorySavedSessionStorage()),
    ): SessionsViewModel {
        val repository =
            SessionIndexRepository(
                remoteDataSource = remote,
                cache = InMemorySessionIndexCache(),
                scope = CoroutineScope(SupervisorJob() + dispatcher),
                minimumRefreshIntervalMs = 0,
            )
        return SessionsViewModel(
            profileStore = profiles,
            tokenStore = FakeTokenStore(),
            repository = repository,
            sessionController = controller,
            cwdPreferenceStore = cwdStore,
            savedStateStore = savedStateStore,
            backgroundDispatcher = dispatcher,
            onResumeStarted = {},
        ).also { viewModels.add(it) }
    }

    private fun stableRecord(
        id: String,
        cwd: String,
    ): SessionRecord =
        SessionRecord(
            sessionPath = "$cwd/session.jsonl",
            cwd = cwd,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-02T00:00:00Z",
            displayName = "$id task",
            sessionId = id,
            isSessionIdUnique = true,
        )

    private fun group(
        cwd: String,
        record: SessionRecord,
    ) = SessionGroup(cwd, listOf(record))

    private class MutableProfileStore(vararg initial: HostProfile) : HostProfileStore {
        private val profiles = initial.associateByTo(linkedMapOf(), HostProfile::id)

        override fun list(): List<HostProfile> = profiles.values.toList()

        override fun upsert(profile: HostProfile) {
            profiles[profile.id] = profile
        }

        override fun delete(hostId: String) {
            profiles.remove(hostId)
        }
    }

    private inner class FakeRemote : SessionIndexRemoteDataSource {
        val groupsByHost = linkedMapOf<String, List<SessionGroup>>()

        fun addRecord(
            hostId: String,
            cwd: String,
            sessionId: String,
        ) {
            groupsByHost[hostId] = listOf(group(cwd, stableRecord(sessionId, cwd)))
        }

        override suspend fun fetch(hostId: String): List<SessionGroup> = groupsByHost[hostId].orEmpty()
    }

    private class FakeTokenStore : HostTokenStore {
        override fun hasToken(hostId: String): Boolean = true

        override fun getToken(hostId: String): String = "token"

        override fun setToken(
            hostId: String,
            token: String,
        ) = Unit

        override fun clearToken(hostId: String) = Unit
    }
}
