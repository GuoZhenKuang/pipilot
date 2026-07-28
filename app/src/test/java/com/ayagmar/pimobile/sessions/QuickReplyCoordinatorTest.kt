package com.ayagmar.pimobile.sessions

import com.ayagmar.pimobile.coresessions.SessionKey
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.hosts.HostProfile
import com.ayagmar.pimobile.hosts.HostTokenStore
import com.ayagmar.pimobile.testutil.FakeSessionController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickReplyCoordinatorTest {
    private val host = HostProfile("host-a", "Alpha", "alpha", 8787, false)
    private val key = SessionKey(host.id, "session-a")
    private val record =
        SessionRecord(
            sessionPath = "/private/session.jsonl",
            cwd = "/private/work",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
            sessionId = key.sessionId,
            isSessionIdUnique = true,
        )

    @Test
    fun `idle target resumes through controller before exactly one prompt dispatch`() =
        runTest {
            val controller = FakeSessionController()
            val coordinator = coordinator(controller)
            coordinator.open(key, "Target")
            coordinator.updateDraft("hello")

            coordinator.send()
            coordinator.send()
            advanceUntilIdle()

            assertEquals(1, controller.resumeCallCount)
            assertEquals(1, controller.sendPromptCallCount)
            assertEquals("hello", controller.lastPromptMessage)
            assertEquals(QuickReplyPhase.SENT, coordinator.state.value.phase)
        }

    @Test
    fun `current active run requires explicit mode and follows retry restriction`() =
        runTest {
            val controller =
                FakeSessionController().apply {
                    setActiveSession(key)
                    setStreaming(true)
                }
            val coordinator = coordinator(controller)
            coordinator.open(key, "Target")
            coordinator.updateDraft("next")
            coordinator.send()
            advanceUntilIdle()
            assertEquals(0, controller.followUpCallCount)
            assertEquals(QuickReplyPhase.EDITING, coordinator.state.value.phase)

            coordinator.selectDeliveryMode(QuickReplyDeliveryMode.FOLLOW_UP)
            coordinator.send()
            advanceUntilIdle()
            assertEquals(1, controller.followUpCallCount)
            assertEquals(0, controller.resumeCallCount)

            coordinator.open(key, "Target")
            coordinator.updateDraft("steer")
            controller.setRetrying(true)
            coordinator.selectDeliveryMode(QuickReplyDeliveryMode.STEER)
            assertEquals(QuickReplyPhase.ERROR, coordinator.state.value.phase)
            assertEquals(0, controller.steerCallCount)
        }

    @Test
    fun `successful steer dispatches once without resume`() =
        runTest {
            val controller =
                FakeSessionController().apply {
                    setActiveSession(key)
                    setStreaming(true)
                }
            val coordinator = coordinator(controller)
            coordinator.open(key, "Target")
            coordinator.updateDraft("redirect")
            coordinator.selectDeliveryMode(QuickReplyDeliveryMode.STEER)

            coordinator.send()
            coordinator.send()
            advanceUntilIdle()

            assertEquals(1, controller.steerCallCount)
            assertEquals(0, controller.resumeCallCount)
            assertEquals(QuickReplyPhase.SENT, coordinator.state.value.phase)
        }

    @Test
    fun `different active run never switches and offers current chat`() =
        runTest {
            val controller =
                FakeSessionController().apply {
                    setActiveSession(SessionKey(host.id, "other-session"))
                    setStreaming(true)
                }
            var opened = 0
            val coordinator = coordinator(controller) { opened += 1 }
            coordinator.open(key, "Target")
            coordinator.updateDraft("hello")
            coordinator.send()
            advanceUntilIdle()

            assertEquals(QuickReplyPhase.CONFLICT, coordinator.state.value.phase)
            assertEquals(0, controller.resumeCallCount)
            assertEquals(0, controller.sendPromptCallCount)
            coordinator.openCurrentChat()
            assertEquals(1, opened)
        }

    @Test
    fun `dismiss and retarget cancel delayed resume before prompt`() =
        runTest {
            val controller = FakeSessionController().apply { resumeDelayMs = 1_000 }
            val coordinator = coordinator(controller)
            coordinator.open(key, "Target")
            coordinator.updateDraft("late")
            coordinator.send()
            advanceTimeBy(100)
            coordinator.dismiss()
            advanceUntilIdle()
            assertEquals(0, controller.sendPromptCallCount)
            assertEquals(QuickReplyPhase.HIDDEN, coordinator.state.value.phase)

            coordinator.open(key, "First")
            coordinator.updateDraft("old")
            coordinator.send()
            advanceTimeBy(100)
            coordinator.open(SessionKey(host.id, "session-b"), "Second")
            advanceUntilIdle()
            assertEquals(0, controller.sendPromptCallCount)
            assertEquals("Second", coordinator.state.value.targetLabel)
        }

    @Test
    fun `missing token stale target lock denial and send failure retain draft without navigation`() =
        runTest {
            val controller = FakeSessionController()
            var opened = 0
            val noToken = coordinator(controller, token = null) { opened += 1 }
            noToken.open(key, "Target")
            noToken.updateDraft("keep me")
            noToken.send()
            advanceUntilIdle()
            assertEquals(QuickReplyPhase.ERROR, noToken.state.value.phase)
            assertEquals("keep me", noToken.state.value.draft)
            assertEquals(0, opened)

            val stale = coordinator(controller, targetRecord = null)
            stale.open(key, "Stale target")
            stale.updateDraft("stale draft")
            stale.send()
            advanceUntilIdle()
            assertEquals(QuickReplyPhase.ERROR, stale.state.value.phase)
            assertEquals("stale draft", stale.state.value.draft)
            assertEquals(0, controller.resumeCallCount)

            controller.resumeResult = Result.failure(IllegalStateException("Control lock denied"))
            val denied = coordinator(controller)
            denied.open(key, "Target")
            denied.updateDraft("lock draft")
            denied.send()
            advanceUntilIdle()
            assertEquals("lock draft", denied.state.value.draft)
            assertEquals(0, controller.sendPromptCallCount)

            controller.resumeResult = Result.success(null)
            controller.sendPromptResult = Result.failure(IllegalStateException("send failed"))
            val failed = coordinator(controller)
            failed.open(key, "Target")
            failed.updateDraft("send draft")
            failed.send()
            advanceUntilIdle()
            assertEquals(QuickReplyPhase.ERROR, failed.state.value.phase)
            assertEquals("send draft", failed.state.value.draft)
            assertTrue(failed.state.value.canOpenChat)
        }

    @Test
    fun `null active key after resume cannot dispatch and running null identity cannot switch`() =
        runTest {
            val idleController = FakeSessionController().apply { publishActiveKeyOnResume = false }
            val idle = coordinator(idleController)
            idle.open(key, "Target")
            idle.updateDraft("verify target")
            idle.send()
            advanceUntilIdle()

            assertEquals(1, idleController.resumeCallCount)
            assertEquals(0, idleController.sendPromptCallCount)
            assertEquals(QuickReplyPhase.ERROR, idle.state.value.phase)

            val runningController =
                FakeSessionController().apply {
                    setStreaming(true)
                    setActiveSession(null)
                }
            val running = coordinator(runningController)
            running.open(key, "Target")
            running.updateDraft("do not switch")
            running.send()
            advanceUntilIdle()

            assertEquals(QuickReplyPhase.CONFLICT, running.state.value.phase)
            assertFalse(running.state.value.canOpenChat)
            assertEquals(0, runningController.resumeCallCount)
            assertEquals(0, runningController.sendPromptCallCount)
        }

    @Test
    fun `existing and concurrent session switches prevent late prompt dispatch`() =
        runTest {
            val alreadySwitching = FakeSessionController().apply { beginSessionSwitch("/switching") }
            val blocked = coordinator(alreadySwitching)
            blocked.open(key, "Target")
            blocked.updateDraft("wait")
            blocked.send()
            advanceUntilIdle()

            assertEquals(QuickReplyPhase.ERROR, blocked.state.value.phase)
            assertEquals(0, alreadySwitching.resumeCallCount)
            assertEquals(0, alreadySwitching.sendPromptCallCount)

            val concurrent =
                FakeSessionController().apply {
                    publishActiveKeyOnResume = false
                    resumeDelayMs = 1_000
                }
            val raced = coordinator(concurrent)
            raced.open(key, "Target")
            raced.updateDraft("do not race")
            raced.send()
            advanceTimeBy(100)
            concurrent.setActiveSession(SessionKey(host.id, "other-session"))
            advanceUntilIdle()

            assertEquals(1, concurrent.resumeCallCount)
            assertEquals(0, concurrent.sendPromptCallCount)
            assertEquals(QuickReplyPhase.ERROR, raced.state.value.phase)
            assertEquals("do not race", raced.state.value.draft)

            val switchedBeforeDispatch =
                FakeSessionController().apply {
                    beforeSendPrompt = { setActiveSession(SessionKey(host.id, "other-session")) }
                }
            val guarded = coordinator(switchedBeforeDispatch)
            guarded.open(key, "Target")
            guarded.updateDraft("guard dispatch")
            guarded.send()
            advanceUntilIdle()

            assertEquals(1, switchedBeforeDispatch.resumeCallCount)
            assertEquals(0, switchedBeforeDispatch.sendPromptCallCount)
            assertEquals(QuickReplyPhase.ERROR, guarded.state.value.phase)
        }

    @Test
    fun `navigation occurs only for explicit send and open or open chat`() =
        runTest {
            val controller = FakeSessionController()
            var opened = 0
            val coordinator = coordinator(controller) { opened += 1 }
            coordinator.open(key, "Target")
            coordinator.updateDraft("send")
            coordinator.send(openAfterSend = false)
            advanceUntilIdle()
            assertEquals(0, opened)

            coordinator.open(key, "Target")
            coordinator.updateDraft("send open")
            coordinator.send(openAfterSend = true)
            advanceUntilIdle()
            assertEquals(1, opened)
            assertFalse(coordinator.state.value.draft.isBlank())
        }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        controller: FakeSessionController,
        token: String? = "token",
        targetRecord: SessionRecord? = record,
        onOpen: () -> Unit = {},
    ): QuickReplyCoordinator {
        val tokens = FakeTokenStore(token)
        return QuickReplyCoordinator(
            controller = controller,
            tokenStore = tokens,
            hostById = { if (it == host.id) host else null },
            recordByKey = { if (it == key) targetRecord else null },
            scope = this,
            onOpenChat = onOpen,
        )
    }

    private class FakeTokenStore(initial: String?) : HostTokenStore {
        private var token = initial

        override fun hasToken(hostId: String) = token != null

        override fun getToken(hostId: String) = token

        override fun setToken(
            hostId: String,
            token: String,
        ) {
            this.token = token
        }

        override fun clearToken(hostId: String) {
            token = null
        }
    }
}
