package top.guozk.pipilot.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostDraftTest {
    @Test
    fun validateAcceptsCompleteHostDraft() {
        val draft =
            HostDraft(
                name = "Laptop",
                host = "100.64.0.10",
                port = "8787",
                useTls = true,
            )

        val validation = draft.validate()

        assertTrue(validation is HostValidationResult.Valid)
        val valid = validation as HostValidationResult.Valid
        assertEquals("Laptop", valid.profile.name)
        assertEquals("100.64.0.10", valid.profile.host)
        assertEquals(8787, valid.profile.port)
        assertEquals(true, valid.profile.useTls)
    }

    @Test
    fun validateRejectsInvalidPort() {
        val draft =
            HostDraft(
                name = "Laptop",
                host = "100.64.0.10",
                port = "99999",
            )

        val validation = draft.validate()

        assertTrue(validation is HostValidationResult.Invalid)
        val invalid = validation as HostValidationResult.Invalid
        assertEquals("端口必须在 1 到 65535 之间", invalid.reason)
    }
}
