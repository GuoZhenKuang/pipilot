package com.ayagmar.pimobile.ui.chat

import com.ayagmar.pimobile.corenet.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScreenHeaderSummaryTest {
    @Test
    fun formatsConnectionSummaryWithoutQueuedMessages() {
        assertEquals(
            "已连接",
            invokeConnectionSummary(ConnectionState.CONNECTED, 0),
        )
    }

    @Test
    fun formatsConnectionSummaryWithQueuedMessages() {
        assertEquals(
            "重新连接中 • 排队中：2 条消息",
            invokeConnectionSummary(ConnectionState.RECONNECTING, 2),
        )
    }

    @Test
    fun formatsSingularQueuedMessageLabel() {
        assertEquals("排队中：1 条消息", invokeQueuedMessagesLabel(1))
    }

    private fun invokeConnectionSummary(
        connectionState: ConnectionState,
        pendingMessageCount: Int,
    ): String {
        val method =
            Class.forName(CHAT_SCREEN_FILE_CLASS)
                .getDeclaredMethod(
                    "formatConnectionSummary",
                    ConnectionState::class.java,
                    Int::class.javaPrimitiveType,
                )
        method.isAccessible = true
        return method.invoke(null, connectionState, pendingMessageCount) as String
    }

    private fun invokeQueuedMessagesLabel(pendingMessageCount: Int): String {
        val method =
            Class.forName(CHAT_SCREEN_FILE_CLASS)
                .getDeclaredMethod("formatQueuedMessagesLabel", Int::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(null, pendingMessageCount) as String
    }

    companion object {
        private const val CHAT_SCREEN_FILE_CLASS = "com.ayagmar.pimobile.ui.chat.ChatScreenKt"
    }
}
