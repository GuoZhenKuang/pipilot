package top.guozk.pipilot.background

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.guozk.pipilot.corenet.ConnectionState
import top.guozk.pipilot.corerpc.AgentSettledEvent
import top.guozk.pipilot.corerpc.AgentStartEvent
import top.guozk.pipilot.coresessions.SessionKey
import top.guozk.pipilot.testutil.FakeSessionController

@OptIn(ExperimentalCoroutinesApi::class)
class RunStateObserverTest {
    private fun TestScope.launchObserver(observer: RunStateObserver) {
        launch { observer.start(this) }
    }

    private fun TestScope.stopObserver(observer: RunStateObserver) {
        observer.stop()
    }

    @Test
    fun `tracks active session identity`() =
        runTest(StandardTestDispatcher()) {
            val controller = FakeSessionController()
            val observer = RunStateObserver(controller)
            launchObserver(observer)

            controller.setActiveSession(SessionKey("host-1", "session-1"))
            advanceUntilIdle()

            assertEquals("host-1", observer.snapshot.value.hostProfileId)
            assertEquals("session-1", observer.snapshot.value.sessionId)
            stopObserver(observer)
            advanceUntilIdle()
        }

    @Test
    fun `streaming phase transitions on agent events`() =
        runTest(StandardTestDispatcher()) {
            val controller = FakeSessionController()
            val observer = RunStateObserver(controller)
            launchObserver(observer)
            advanceUntilIdle()

            controller.emitEvent(AgentStartEvent(type = "agent_start"))
            advanceUntilIdle()
            assertEquals(RunStateObserver.Phase.STREAMING, observer.snapshot.value.phase)

            controller.emitEvent(AgentSettledEvent(type = "agent_settled"))
            advanceUntilIdle()
            assertEquals(RunStateObserver.Phase.IDLE, observer.snapshot.value.phase)
            stopObserver(observer)
            advanceUntilIdle()
        }

    @Test
    fun `connection state feeds snapshot`() =
        runTest(StandardTestDispatcher()) {
            val controller = FakeSessionController()
            val observer = RunStateObserver(controller)
            launchObserver(observer)

            controller.setConnectionState(ConnectionState.CONNECTED)
            advanceUntilIdle()
            assertEquals(true, observer.snapshot.value.connected)
            stopObserver(observer)
            advanceUntilIdle()
        }

    @Test
    fun `snapshot starts empty without identity`() =
        runTest(StandardTestDispatcher()) {
            val controller = FakeSessionController()
            val observer = RunStateObserver(controller)
            launchObserver(observer)
            advanceUntilIdle()

            assertNull(observer.snapshot.value.hostProfileId)
            assertNull(observer.snapshot.value.sessionId)
            assertEquals(RunStateObserver.Phase.IDLE, observer.snapshot.value.phase)
            stopObserver(observer)
            advanceUntilIdle()
        }
}
