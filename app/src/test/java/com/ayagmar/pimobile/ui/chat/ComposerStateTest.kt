package com.ayagmar.pimobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerStateTest {
    @Test
    fun `follow up submission trims and preserves the message`() {
        val submission = createActiveRunSubmission("  next  ", ActiveRunDeliveryMode.FOLLOW_UP)

        assertEquals(
            ActiveRunSubmission("next", ActiveRunDeliveryMode.FOLLOW_UP),
            submission,
        )
    }

    @Test
    fun `steer submission retains selected mode`() {
        val submission = createActiveRunSubmission("narrow scope", ActiveRunDeliveryMode.STEER)

        assertEquals(ActiveRunDeliveryMode.STEER, submission?.deliveryMode)
    }

    @Test
    fun `empty submission is rejected`() {
        assertNull(createActiveRunSubmission("  ", ActiveRunDeliveryMode.STEER))
    }
}
