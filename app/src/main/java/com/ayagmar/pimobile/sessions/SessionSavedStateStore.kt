@file:Suppress("ReturnCount")

package com.ayagmar.pimobile.sessions

import android.content.Context
import androidx.core.content.edit
import com.ayagmar.pimobile.coresessions.SessionKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val SAVED_STATE_VERSION = 1
private const val MAX_SAVED_KEYS = 2_000
private const val MAX_RAW_LENGTH = 1_048_576
private const val MAX_PROFILE_ID_LENGTH = 128

enum class SessionCardDensity {
    COMFORTABLE,
    COMPACT,
}

data class SavedSessionsState(
    val pinned: Set<SessionKey> = emptySet(),
    val hidden: Set<SessionKey> = emptySet(),
    val density: SessionCardDensity = SessionCardDensity.COMFORTABLE,
) {
    fun normalized(): SavedSessionsState =
        copy(
            pinned = pinned.take(MAX_SAVED_KEYS).toSet() - hidden,
            hidden = hidden.take(MAX_SAVED_KEYS).toSet(),
        )

    fun pin(key: SessionKey): SavedSessionsState = copy(pinned = pinned + key, hidden = hidden - key).normalized()

    fun unpin(key: SessionKey): SavedSessionsState = copy(pinned = pinned - key).normalized()

    fun hide(key: SessionKey): SavedSessionsState = copy(hidden = hidden + key, pinned = pinned - key).normalized()

    fun unhide(key: SessionKey): SavedSessionsState = copy(hidden = hidden - key).normalized()

    fun remove(key: SessionKey): SavedSessionsState = copy(pinned = pinned - key, hidden = hidden - key).normalized()
}

interface SavedSessionStorage {
    fun read(): String?

    fun write(value: String)
}

class InMemorySavedSessionStorage(
    initialValue: String? = null,
) : SavedSessionStorage {
    var value: String? = initialValue
        private set

    override fun read(): String? = value

    override fun write(value: String) {
        this.value = value
    }
}

class SharedPreferencesSavedSessionStorage(
    context: Context,
) : SavedSessionStorage {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(STATE_KEY, null)

    override fun write(value: String) {
        preferences.edit { putString(STATE_KEY, value) }
    }

    private companion object {
        const val PREFERENCES_NAME = "pi_mobile_saved_sessions"
        const val STATE_KEY = "saved_session_keys"
    }
}

class SessionSavedStateStore(
    private val storage: SavedSessionStorage,
    private val json: Json = defaultJson,
) {
    fun read(): SavedSessionsState {
        val raw = storage.read() ?: return SavedSessionsState()
        if (raw.length > MAX_RAW_LENGTH) return SavedSessionsState()
        return runCatching {
            val persisted = json.decodeFromString<PersistedSavedSessions>(raw)
            require(persisted.version == SAVED_STATE_VERSION) { "Unsupported saved-session state" }
            SavedSessionsState(
                pinned = persisted.pinned.decodeKeys(),
                hidden = persisted.hidden.decodeKeys(),
                density =
                    runCatching { SessionCardDensity.valueOf(persisted.density) }
                        .getOrDefault(SessionCardDensity.COMFORTABLE),
            ).normalized()
        }.getOrDefault(SavedSessionsState())
    }

    fun write(state: SavedSessionsState) {
        val normalized = state.normalized()
        storage.write(
            json.encodeToString(
                PersistedSavedSessions(
                    version = SAVED_STATE_VERSION,
                    density = normalized.density.name,
                    pinned = normalized.pinned.encodeKeys(),
                    hidden = normalized.hidden.encodeKeys(),
                ),
            ),
        )
    }

    fun reconcileConfiguredHosts(configuredHostIds: Set<String>): SavedSessionsState {
        val current = read()
        val reconciled =
            current.copy(
                pinned = current.pinned.filterTo(linkedSetOf()) { it.hostProfileId in configuredHostIds },
                hidden = current.hidden.filterTo(linkedSetOf()) { it.hostProfileId in configuredHostIds },
            ).normalized()
        if (reconciled != current) write(reconciled)
        return reconciled
    }

    private companion object {
        val defaultJson = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class PersistedSavedSessions(
    val version: Int,
    val density: String,
    val pinned: List<PersistedSessionKey>,
    val hidden: List<PersistedSessionKey>,
)

@Serializable
private data class PersistedSessionKey(
    val hostProfileId: String,
    val sessionId: String,
)

private fun Set<SessionKey>.encodeKeys(): List<PersistedSessionKey> =
    take(MAX_SAVED_KEYS).map { PersistedSessionKey(it.hostProfileId, it.sessionId) }

private fun List<PersistedSessionKey>.decodeKeys(): Set<SessionKey> =
    take(MAX_SAVED_KEYS).mapNotNullTo(linkedSetOf()) { key ->
        if (key.hostProfileId.isBlank() || key.hostProfileId.length > MAX_PROFILE_ID_LENGTH) {
            null
        } else {
            runCatching { SessionKey(key.hostProfileId, key.sessionId) }.getOrNull()
        }
    }
