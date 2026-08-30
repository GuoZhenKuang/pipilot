package top.guozk.pipilot.ui.chat

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

        assertTrue(summary.contains("会话：Chat"))
        assertTrue(summary.contains("工作目录：/work/project"))
        assertTrue(summary.contains("Pi 正在工作"))
    }

    @Test
    fun `omits missing metadata while retaining idle state`() {
        val summary = formatHandoffSummary(HandoffSummaryData(null, null, null, null, HandoffRunStatus.IDLE))
        assertTrue(summary.contains("Pi 处于空闲状态"))
        assertFalse(summary.contains("null"))
    }

    @Test
    fun `retrying state is explicit`() {
        val summary =
            formatHandoffSummary(
                HandoffSummaryData(null, null, null, null, HandoffRunStatus.RETRYING),
            )

        assertTrue(summary.contains("Pi 正在重试"))
        assertFalse(summary.contains("Pi 正在等待"))
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
