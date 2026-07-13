package com.ayagmar.pimobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineAutoScrollReducerTest {
    @Test
    fun `near-bottom activity retains sticky behavior`() {
        val state = reduceTimelineReadingState(TimelineReadingState(), TimelineReadingAction.NewActivity())
        assertTrue(state.sticksToBottom)
        assertEquals(0, state.unreadCount)
    }

    @Test
    fun `scrolled-away activity increments unread count`() {
        val away = reduceTimelineReadingState(TimelineReadingState(), TimelineReadingAction.ScrollAway)
        val first = reduceTimelineReadingState(away, TimelineReadingAction.NewActivity())
        val second = reduceTimelineReadingState(first, TimelineReadingAction.NewActivity())
        assertFalse(second.sticksToBottom)
        assertEquals(2, second.unreadCount)
    }

    @Test
    fun `disclosure changes do not count as activity`() {
        val away = TimelineReadingState(sticksToBottom = false)
        assertEquals(away, reduceTimelineReadingState(away, TimelineReadingAction.DisclosureChanged))
    }

    @Test
    fun `jump resets unread and restores sticky bottom`() {
        val state = TimelineReadingState(sticksToBottom = false, unreadCount = 4)
        assertEquals(TimelineReadingState(), reduceTimelineReadingState(state, TimelineReadingAction.JumpToLatest))
    }

    @Test
    fun `batched activity increments by newly added items`() {
        val away = TimelineReadingState(sticksToBottom = false, unreadCount = 2)

        val updated = reduceTimelineReadingState(away, TimelineReadingAction.NewActivity(count = 3))

        assertEquals(5, updated.unreadCount)
    }

    @Test
    fun `streaming text growth does not create another activity identity`() {
        val before =
            timelineActivityIdentities(
                listOf(assistant(id = "answer", text = "Hello", streaming = true)),
            )
        val after =
            timelineActivityIdentities(
                listOf(assistant(id = "answer", text = "Hello there", streaming = true)),
            )

        assertEquals(0, countNewTimelineActivities(before, after))
    }

    @Test
    fun `prepended history is not counted as new activity`() {
        val before = listOf("user:current", "assistant:answer")
        val after = listOf("user:older", "assistant:older-answer") + before

        assertEquals(0, countNewTimelineActivities(before, after))
    }

    @Test
    fun `new activity identity is counted once`() {
        val before = timelineActivityIdentities(listOf(assistant(id = "answer", text = "Hello")))
        val after = before + "tool:read"

        assertEquals(1, countNewTimelineActivities(before, after))
    }

    private fun assistant(
        id: String,
        text: String,
        streaming: Boolean = false,
    ) = com.ayagmar.pimobile.chat.ChatTimelineItem.Assistant(
        id = id,
        text = text,
        isStreaming = streaming,
    )
}
