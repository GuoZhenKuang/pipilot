package com.ayagmar.pimobile.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPairingPayloadTest {
    @Test
    fun parseCreatesCompleteHostDraft() {
        val result = parseHostPairingPayload(VALID_PAYLOAD)

        assertEquals(
            HostDraft(
                name = "workstation",
                host = "workstation.example.ts.net",
                port = "8787",
                useTls = false,
                token = "test-token",
            ),
            result.getOrThrow(),
        )
    }

    @Test
    fun parseRejectsUnrelatedQrCode() {
        val result = parseHostPairingPayload("https://example.com")

        assertTrue(result.isFailure)
    }

    @Test
    fun parseRejectsPayloadWithoutToken() {
        val result = parseHostPairingPayload(PAYLOAD_WITHOUT_TOKEN)

        assertTrue(result.isFailure)
    }

    private companion object {
        const val VALID_PAYLOAD =
            """{"type":"pi-mobile-host","version":1,"name":"workstation",""" +
                """"host":"workstation.example.ts.net","port":8787,"useTls":false,"token":"test-token"}"""
        const val PAYLOAD_WITHOUT_TOKEN =
            """{"type":"pi-mobile-host","version":1,"name":"workstation",""" +
                """"host":"workstation.example.ts.net","port":8787,"useTls":false}"""
    }
}
