package com.ayagmar.pimobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineAutoScrollReducerTest {
    @Test
    fun `near-bottom activity retains sticky behavior`() {
        val state = reduceTimelineReadingState(TimelineReadingState(), TimelineReadingAction.NewActivity)
        assertTrue(state.sticksToBottom)
        assertEquals(0, state.unreadCount)
    }

    @Test
    fun `scrolled-away activity increments unread count`() {
        val away = reduceTimelineReadingState(TimelineReadingState(), TimelineReadingAction.ScrollAway)
        val first = reduceTimelineReadingState(away, TimelineReadingAction.NewActivity)
        val second = reduceTimelineReadingState(first, TimelineReadingAction.NewActivity)
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
    fun `prepend shifts anchor by inserted row count`() {
        assertEquals(9, preservedIndexAfterPrepend(previousIndex = 3, prependedCount = 6))
    }
}
