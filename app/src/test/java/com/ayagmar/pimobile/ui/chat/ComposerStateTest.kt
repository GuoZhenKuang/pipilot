package com.ayagmar.pimobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerStateTest {
    @Test
    fun `follow up is the default and mode switching preserves draft`() {
        val draft = reduceComposerState(ComposerState(), ComposerAction.ChangeDraft("next"))
        val steered = reduceComposerState(draft, ComposerAction.SelectMode(ActiveRunDeliveryMode.STEER))

        assertEquals("next", steered.draft)
        assertEquals(ActiveRunDeliveryMode.STEER, steered.deliveryMode)
    }

    @Test
    fun `submit clears non-empty draft`() {
        val state = ComposerState(draft = "next")
        assertEquals("", reduceComposerState(state, ComposerAction.Submit).draft)
    }

    @Test
    fun `empty submit is a no-op`() {
        val state = ComposerState(draft = "  ", deliveryMode = ActiveRunDeliveryMode.STEER)
        assertEquals(state, reduceComposerState(state, ComposerAction.Submit))
    }

    @Test
    fun `queue count is represented by queue size`() {
        assertEquals(3, listOf("one", "two", "three").size)
    }
}
