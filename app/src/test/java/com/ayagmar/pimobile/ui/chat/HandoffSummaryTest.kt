package com.ayagmar.pimobile.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffSummaryTest {
    @Test
    fun `formats available metadata and active state`() {
        val summary =
            formatHandoffSummary(
                HandoffSummaryData(
                    sessionName = "Chat",
                    cwd = "/work/project",
                    sessionPath = "/sessions/chat.jsonl",
                    model = "provider/model",
                    runStatus = HandoffRunStatus.WORKING,
                ),
            )

        assertTrue(summary.contains("Session: Chat"))
        assertTrue(summary.contains("Working directory: /work/project"))
        assertTrue(summary.contains("Pi is working"))
    }

    @Test
    fun `omits missing metadata while retaining idle state`() {
        val summary = formatHandoffSummary(HandoffSummaryData(null, null, null, null, HandoffRunStatus.IDLE))
        assertTrue(summary.contains("Pi is idle"))
        assertFalse(summary.contains("null"))
    }

    @Test
    fun `retrying state is explicit`() {
        val summary =
            formatHandoffSummary(
                HandoffSummaryData(null, null, null, null, HandoffRunStatus.RETRYING),
            )

        assertTrue(summary.contains("Pi is retrying"))
        assertFalse(summary.contains("Pi is waiting"))
    }

    @Test
    fun `model has no sensitive or internal handoff fields`() {
        val fields = HandoffSummaryData::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("token", ignoreCase = true) })
        assertFalse(fields.any { it.contains("owner", ignoreCase = true) })
        assertFalse(fields.any { it.contains("host", ignoreCase = true) })
        val summary =
            formatHandoffSummary(
                HandoffSummaryData(null, null, null, null, HandoffRunStatus.RETRYING),
            )
        assertFalse(summary.contains("pi --"))
    }
}
