package com.ayagmar.pimobile.ui.chat

import com.ayagmar.pimobile.chat.ChatTimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineAutoScrollReducerTest {
    @Test
    fun `near-bottom activity retains sticky behavior`() {
        val state =
            reduceTimelineReadingState(
                TimelineReadingState(),
                TimelineReadingAction.NewActivity(TimelineUnreadDelta(assistantCount = 1)),
            )
        assertTrue(state.sticksToBottom)
        assertEquals(0, state.unreadCount)
    }

    @Test
    fun `scrolled-away activity tracks replies and tools separately`() {
        val away = reduceTimelineReadingState(TimelineReadingState(), TimelineReadingAction.ScrollAway)
        val updated =
            reduceTimelineReadingState(
                away,
                TimelineReadingAction.NewActivity(
                    TimelineUnreadDelta(assistantCount = 2, toolCount = 3),
                ),
            )

        assertFalse(updated.sticksToBottom)
        assertEquals(2, updated.assistantUnreadCount)
        assertEquals(3, updated.toolUnreadCount)
        assertEquals("2 条回复 · 3 个工具", formatUnreadActivityLabel(updated))
    }

    @Test
    fun `disclosure interaction pauses sticky scrolling without adding unread activity`() {
        val state =
            TimelineReadingState(
                sticksToBottom = true,
                assistantUnreadCount = 2,
            )

        val paused = reduceTimelineReadingState(state, TimelineReadingAction.DisclosureChanged)

        assertFalse(paused.sticksToBottom)
        assertEquals(2, paused.unreadCount)
    }

    @Test
    fun `jump resets unread and restores sticky bottom`() {
        val state = TimelineReadingState(sticksToBottom = false, toolUnreadCount = 4)
        assertEquals(TimelineReadingState(), reduceTimelineReadingState(state, TimelineReadingAction.JumpToLatest))
    }

    @Test
    fun `streaming text growth does not create another activity identity`() {
        val before = timelineActivityIdentities(listOf(assistant(id = "answer", text = "Hello", streaming = true)))
        val after = timelineActivityIdentities(listOf(assistant(id = "answer", text = "Hello there", streaming = true)))

        assertEquals(TimelineUnreadDelta(), countNewTimelineActivities(before, after))
    }

    @Test
    fun `prepended history is not counted as new activity`() {
        val before = listOf("user:current", "assistant:answer")
        val after = listOf("user:older", "assistant:older-answer") + before

        assertEquals(TimelineUnreadDelta(), countNewTimelineActivities(before, after))
    }

    @Test
    fun `new tool activity is classified separately`() {
        val before = timelineActivityIdentities(listOf(assistant(id = "answer", text = "Hello")))
        val after = before + "tool:read"

        assertEquals(TimelineUnreadDelta(toolCount = 1), countNewTimelineActivities(before, after))
        assertEquals("1 个工具", formatUnreadActivityLabel(TimelineReadingState(toolUnreadCount = 1)))
    }

    private fun assistant(
        id: String,
        text: String,
        streaming: Boolean = false,
    ) = ChatTimelineItem.Assistant(
        id = id,
        text = text,
        isStreaming = streaming,
    )
}
