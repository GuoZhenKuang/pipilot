package com.ayagmar.pimobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChatTurnProjectionTest {
    @Test
    fun `keeps thinking tool and final answer ordered in one turn`() {
        val user = user("u1")
        val thinking = assistant("a1", thinking = "Working")
        val tool = tool("t1")
        val answer = assistant("a2", text = "Done")

        val turn = projectChatTurns(listOf(user, thinking, tool, answer)).single()

        assertSame(user, turn.user)
        assertEquals(listOf(thinking, tool, answer), turn.activity)
        assertEquals(listOf(tool), turn.tools)
    }

    @Test
    fun `retains multiple tools and tool errors`() {
        val turns = projectChatTurns(listOf(user("u"), tool("one"), tool("two", error = true)))

        assertEquals(2, turns.single().tools.size)
        assertEquals(ChatTurnRunState.ERROR, turns.single().runState)
    }

    @Test
    fun `retains consecutive assistant items`() {
        val turn = projectChatTurns(listOf(user("u"), assistant("a1"), assistant("a2"))).single()
        assertEquals(listOf("a1", "a2"), turn.assistants.map { it.id })
    }

    @Test
    fun `creates stable orphan turn for leading activity`() {
        val timeline = listOf(assistant("a"), tool("t"))
        val first = projectChatTurns(timeline).single()
        val updated = projectChatTurns(listOf(assistant("a", text = "longer"), tool("t"))).single()

        assertEquals("orphan:a", first.key)
        assertEquals(first.key, updated.key)
        assertEquals(null, first.user)
    }

    @Test
    fun `never merges across user boundaries`() {
        val turns = projectChatTurns(listOf(user("u1"), assistant("a1"), user("u2"), tool("t2")))

        assertEquals(listOf("turn:u1", "turn:u2"), turns.map { it.key })
        assertEquals(listOf("a1"), turns[0].activity.map { it.id })
        assertEquals(listOf("t2"), turns[1].activity.map { it.id })
    }

    @Test
    fun `streaming growth retains turn key`() {
        val before = projectChatTurns(listOf(user("u"), assistant("a", text = "Hi", streaming = true)))
        val after = projectChatTurns(listOf(user("u"), assistant("a", text = "Hi there", streaming = true)))

        assertEquals(before.single().key, after.single().key)
        assertEquals(ChatTurnRunState.STREAMING, after.single().runState)
    }

    private fun user(id: String) = ChatTimelineItem.User(id = id, text = id)

    private fun assistant(
        id: String,
        text: String = "",
        thinking: String? = null,
        streaming: Boolean = false,
    ) = ChatTimelineItem.Assistant(
        id = id,
        text = text,
        thinking = thinking,
        isStreaming = streaming,
    )

    private fun tool(
        id: String,
        error: Boolean = false,
    ) = ChatTimelineItem.Tool(
        id = id,
        toolName = "read",
        output = id,
        isCollapsed = true,
        isStreaming = false,
        isError = error,
    )
}
