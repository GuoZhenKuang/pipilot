package com.ayagmar.pimobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTimelineScrollTest {
    @Test
    fun bottomAnchorIncludesLoadOlderAndRunProgressRows() {
        assertEquals(
            12,
            timelineBottomAnchorIndex(
                timelineSize = 10,
                hasOlderMessages = true,
                showInlineRunProgress = true,
            ),
        )
    }

    @Test
    fun bottomAnchorFollowsTimelineWithoutOptionalRows() {
        assertEquals(
            10,
            timelineBottomAnchorIndex(
                timelineSize = 10,
                hasOlderMessages = false,
                showInlineRunProgress = false,
            ),
        )
    }
}
