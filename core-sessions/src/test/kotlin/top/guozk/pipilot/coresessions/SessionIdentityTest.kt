package top.guozk.pipilot.coresessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionIdentityTest {
    @Test
    fun `locator round trips authority and opaque reference without local identity`() {
        val locator =
            SharedSessionLocator(
                authority = ShareAuthority("example.com", 8787, tls = true),
                shareReference = "AbCdEfGhIjKlMnOpQrStUv",
            )
        val encoded = SharedSessionLocatorCodec.encode(locator)
        val decoded = SharedSessionLocatorCodec.decode(encoded).getOrThrow()

        assertEquals(locator, decoded)
        assertFalse(encoded.contains("session-id"))
        assertFalse(encoded.contains("/tmp"))
        assertFalse(encoded.contains("profile-id"))
    }

    @Test
    fun `locator rejects duplicate parameters unknown versions and raw session ids`() {
        val reference = "AbCdEfGhIjKlMnOpQrStUv"
        val base = "pimobile://open/v1/$reference?host=example.com&port=8787&tls=1"
        assertTrue(SharedSessionLocatorCodec.decode(base).isSuccess)
        assertTrue(SharedSessionLocatorCodec.decode(base.replace("&tls=1", "&tls=1&tls=1")).isFailure)
        assertTrue(SharedSessionLocatorCodec.decode(base.replace("/v1/", "/v2/")).isFailure)
        assertTrue(SharedSessionLocatorCodec.decode(base.replace(reference, "raw-session-id")).isFailure)
    }

    @Test
    fun `session key accepts only valid internal ids`() {
        val key = SessionKey("local-profile", "pi-session-id")
        assertEquals("pi-session-id", key.sessionId)
        assertFalse("bad id".isValidPiSessionId())
        assertFalse("".isValidPiSessionId())
    }
}
