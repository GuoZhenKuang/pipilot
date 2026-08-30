package top.guozk.pipilot.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.guozk.pipilot.coresessions.SessionKey

class SessionSavedStateStoreTest {
    @Test
    fun `round trip stores keys and presentation preference only`() {
        val storage = InMemorySavedSessionStorage()
        val store = SessionSavedStateStore(storage)
        val pinned = SessionKey("host-a", "session-a")
        val hidden = SessionKey("host-b", "session-b")

        store.write(SavedSessionsState(setOf(pinned), setOf(hidden), SessionCardDensity.COMPACT))

        assertEquals(SavedSessionsState(setOf(pinned), setOf(hidden), SessionCardDensity.COMPACT), store.read())
        val raw = requireNotNull(storage.value)
        assertTrue(raw.contains("hostProfileId"))
        assertTrue(raw.contains("sessionId"))
        listOf("sessionPath", "cwd", "title", "preview", "transcript", "authority", "token").forEach {
            assertFalse(raw.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `corruption and oversized data fall back without rewriting storage`() {
        val corrupt = InMemorySavedSessionStorage("not-json")
        assertEquals(SavedSessionsState(), SessionSavedStateStore(corrupt).read())
        assertEquals("not-json", corrupt.value)

        val oversizedValue = "x".repeat(1_048_577)
        val oversized = InMemorySavedSessionStorage(oversizedValue)
        assertEquals(SavedSessionsState(), SessionSavedStateStore(oversized).read())
        assertEquals(oversizedValue, oversized.value)
    }

    @Test
    fun `host deletion clears only deleted profile scope while endpoint edits preserve keys`() {
        val storage = InMemorySavedSessionStorage()
        val store = SessionSavedStateStore(storage)
        val retained = SessionKey("stable-profile", "session-a")
        val deleted = SessionKey("deleted-profile", "session-b")
        store.write(SavedSessionsState(pinned = setOf(retained, deleted), hidden = setOf(deleted)))

        val reconciled = store.reconcileConfiguredHosts(setOf("stable-profile"))

        assertEquals(setOf(retained), reconciled.pinned)
        assertTrue(reconciled.hidden.isEmpty())
        assertEquals(reconciled, store.reconcileConfiguredHosts(setOf("stable-profile")))
    }

    @Test
    fun `pin and hide transitions are mutually exclusive and always recoverable`() {
        val key = SessionKey("host-a", "session-a")
        val hidden = SavedSessionsState().hide(key)
        assertTrue(key in hidden.hidden)
        assertFalse(key in hidden.pinned)

        val pinned = hidden.pin(key)
        assertTrue(key in pinned.pinned)
        assertFalse(key in pinned.hidden)

        val hiddenAgain = pinned.hide(key)
        assertTrue(key in hiddenAgain.hidden)
        assertFalse(key in hiddenAgain.pinned)
        assertEquals(SavedSessionsState(), hiddenAgain.remove(key))
    }

    @Test
    fun `hidden wins if corrupted state contains the same key in both sets`() {
        val key = SessionKey("host-a", "session-a")
        val state = SavedSessionsState(pinned = setOf(key), hidden = setOf(key)).normalized()
        assertTrue(key in state.hidden)
        assertFalse(key in state.pinned)
    }
}
