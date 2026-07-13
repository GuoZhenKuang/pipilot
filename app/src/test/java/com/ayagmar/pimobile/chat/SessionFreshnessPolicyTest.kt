package com.ayagmar.pimobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFreshnessPolicyTest {
    @Test
    fun `unchanged fingerprint only updates baseline`() {
        assertAction(SessionFreshnessAction.UPDATE_BASELINE, changed = false)
    }

    @Test
    fun `local grace window only updates baseline`() {
        assertAction(SessionFreshnessAction.UPDATE_BASELINE, grace = true)
    }

    @Test
    fun `explicit other owner shows conflict`() {
        assertAction(SessionFreshnessAction.SHOW_CONFLICT, otherOwns = true)
    }

    @Test
    fun `other owner remains a conflict when current client owns another lock scope`() {
        assertAction(SessionFreshnessAction.SHOW_CONFLICT, currentOwns = true, otherOwns = true)
    }

    @Test
    fun `unattributed random fingerprint change refreshes silently while idle`() {
        assertAction(SessionFreshnessAction.REFRESH_SILENTLY)
    }

    @Test
    fun `unattributed fingerprint change defers while busy`() {
        assertAction(SessionFreshnessAction.DEFER_REFRESH, busy = true)
    }

    @Test
    fun `deferred refresh applies only after chat becomes idle`() {
        assertEquals(false, shouldApplyDeferredFreshnessRefresh(hasDeferredRefresh = true, chatIsBusy = true))
        assertEquals(true, shouldApplyDeferredFreshnessRefresh(hasDeferredRefresh = true, chatIsBusy = false))
        assertEquals(false, shouldApplyDeferredFreshnessRefresh(hasDeferredRefresh = false, chatIsBusy = false))
    }

    @Test
    fun `repeated explicit mismatch has the same conflict classification`() {
        repeat(2) {
            assertAction(SessionFreshnessAction.SHOW_CONFLICT, otherOwns = true)
        }
    }

    @Suppress("LongParameterList")
    private fun assertAction(
        expected: SessionFreshnessAction,
        changed: Boolean = true,
        currentOwns: Boolean = false,
        otherOwns: Boolean = false,
        busy: Boolean = false,
        grace: Boolean = false,
    ) {
        assertEquals(
            expected,
            classifySessionFreshness(
                SessionFreshnessPolicyInput(
                    fingerprintChanged = changed,
                    currentClientOwnsLock = currentOwns,
                    differentClientOwnsLock = otherOwns,
                    chatIsBusy = busy,
                    insideLocalMutationGraceWindow = grace,
                ),
            ),
        )
    }
}
