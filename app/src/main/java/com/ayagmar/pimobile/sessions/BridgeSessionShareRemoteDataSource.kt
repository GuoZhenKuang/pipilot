package com.ayagmar.pimobile.sessions

import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corenet.SocketTransport
import com.ayagmar.pimobile.corenet.WebSocketTarget
import com.ayagmar.pimobile.corenet.WebSocketTransport
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.hosts.HostProfile
import com.ayagmar.pimobile.hosts.HostProfileStore
import com.ayagmar.pimobile.hosts.HostTokenStore
import com.ayagmar.pimobile.hosts.normalizeShareOrigin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class BridgeShareException(
    val code: String,
    message: String,
) : IllegalStateException(message)

data class SessionShare(
    val shareReference: String,
    val webUrl: String?,
)

class BridgeSessionShareRemoteDataSource(
    private val profileStore: HostProfileStore,
    private val tokenStore: HostTokenStore,
    private val transportFactory: () -> SocketTransport = { WebSocketTransport() },
    private val json: Json = defaultJson,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) {
    private val transportsByHost = linkedMapOf<String, SocketTransport>()
    private val mutexesByHost = linkedMapOf<String, Mutex>()
    private val verifiedHelloByHost = linkedMapOf<String, String?>()

    suspend fun getOrCreate(
        hostId: String,
        session: SessionRecord,
    ): SessionShare {
        check(session.hasStableIdentity) { "This session does not have a unique stable identity" }
        val payload =
            request(hostId, "bridge_get_or_create_session_share") {
                put("sessionPath", session.sessionPath)
            }
        val response = json.decodeFromJsonElement(SharePayload.serializer(), payload)
        return SessionShare(response.shareReference, response.webUrl)
    }

    suspend fun resolve(
        hostId: String,
        shareReference: String,
    ): SessionRecord {
        val payload =
            request(hostId, "bridge_resolve_session_share") {
                put("shareReference", shareReference)
            }
        return json.decodeFromJsonElement(ResolvedPayload.serializer(), payload).session
    }

    suspend fun revoke(
        hostId: String,
        session: SessionRecord,
    ) {
        check(session.hasStableIdentity) { "This session does not have a unique stable identity" }
        request(hostId, "bridge_revoke_session_share") {
            put("sessionPath", session.sessionPath)
        }
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod", "ThrowsCount")
    private suspend fun request(
        hostId: String,
        type: String,
        body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val profile =
            profileStore.list().firstOrNull { candidate -> candidate.id == hostId }
                ?: throw BridgeShareException("host_unconfigured", "Review and save this host before connecting")
        val token = tokenStore.getToken(hostId)
        if (token.isNullOrBlank()) throw BridgeShareException("missing_token", "Enter a token for this configured host")
        val mutex = synchronized(mutexesByHost) { mutexesByHost.getOrPut(hostId) { Mutex() } }

        return mutex.withLock {
            val transport = synchronized(transportsByHost) { transportsByHost.getOrPut(hostId, transportFactory) }
            try {
                coroutineScope {
                    val incoming = Channel<JsonObject>(Channel.UNLIMITED)
                    val collector =
                        launch {
                            transport.inboundMessages.mapNotNull(::parseBridgePayload).collect(incoming::send)
                        }
                    try {
                        if (transport.connectionState.value != ConnectionState.CONNECTED) {
                            transport.connect(
                                WebSocketTarget(
                                    url = profile.endpoint,
                                    headers = mapOf(AUTHORIZATION_HEADER to "Bearer $token"),
                                    connectTimeoutMs = connectTimeoutMs,
                                ),
                            )
                            withTimeout(connectTimeoutMs) {
                                transport.connectionState.first { state -> state == ConnectionState.CONNECTED }
                            }
                            val hello = awaitPayload(incoming) { payload -> payload.type() == BRIDGE_HELLO_TYPE }
                            verifyAuthenticatedHello(profile, hello)
                        } else if (!verifiedHelloByHost.containsKey(hostId)) {
                            error("Authenticated bridge capability state is unavailable")
                        }

                        transport.send(
                            json.encodeToString(
                                JsonObject.serializer(),
                                buildJsonObject {
                                    put("channel", BRIDGE_CHANNEL)
                                    put(
                                        "payload",
                                        buildJsonObject {
                                            put("type", type)
                                            body()
                                        },
                                    )
                                },
                            ),
                        )
                        awaitPayload(incoming) { payload ->
                            payload.type() in RESPONSE_TYPES || payload.type() == BRIDGE_ERROR_TYPE
                        }.also { payload ->
                            if (payload.type() == BRIDGE_ERROR_TYPE) throw decodeBridgeError(payload)
                        }
                    } finally {
                        collector.cancel()
                        incoming.close()
                    }
                }
            } catch (error: Throwable) {
                transport.disconnect()
                synchronized(transportsByHost) {
                    if (transportsByHost[hostId] === transport) transportsByHost.remove(hostId)
                }
                synchronized(verifiedHelloByHost) { verifiedHelloByHost.remove(hostId) }
                throw error
            }
        }
    }

    private suspend fun awaitPayload(
        incoming: Channel<JsonObject>,
        predicate: (JsonObject) -> Boolean,
    ): JsonObject =
        withTimeout(requestTimeoutMs) {
            while (true) {
                val payload = incoming.receive()
                if (predicate(payload)) return@withTimeout payload
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable")
        }

    private fun verifyAuthenticatedHello(
        profile: HostProfile,
        payload: JsonObject,
    ) {
        val reported =
            payload["shareOrigin"]?.jsonPrimitive?.contentOrNull
                ?.let(::normalizeShareOrigin)
        val stored = profile.shareOrigin?.let(::normalizeShareOrigin)
        if (stored != null && stored != reported) {
            throw BridgeShareException(
                "share_origin_mismatch",
                "The bridge share origin changed. Review the saved host before opening links.",
            )
        }
        if (stored == null && reported != null) {
            profileStore.upsert(profile.copy(shareOrigin = reported))
        }
        synchronized(verifiedHelloByHost) { verifiedHelloByHost[profile.id] = reported }
    }

    private fun parseBridgePayload(raw: String): JsonObject? =
        runCatching { json.decodeFromString(BridgeEnvelope.serializer(), raw) }
            .getOrNull()
            ?.takeIf { envelope -> envelope.channel == BRIDGE_CHANNEL }
            ?.payload

    private fun decodeBridgeError(payload: JsonObject): BridgeShareException {
        val error = json.decodeFromJsonElement(BridgeErrorPayload.serializer(), payload)
        val message =
            when (error.code) {
                "share_not_found" -> "This shared session is unavailable or was revoked"
                "share_state_unavailable" -> "Sharing is unavailable until the bridge operator repairs its state"
                "session_identity_ambiguous" -> "This session has a duplicate identity and cannot be shared"
                "session_not_shareable" -> "This session does not have a stable identity yet"
                "control_lock_denied", "control_lock_required" -> "This session is open elsewhere"
                else -> "The bridge could not complete the share request"
            }
        return BridgeShareException(error.code ?: "share_operation_failed", message)
    }

    private fun JsonObject.type(): String? = get("type")?.jsonPrimitive?.contentOrNull

    @Serializable
    private data class BridgeEnvelope(
        val channel: String,
        val payload: JsonObject,
    )

    @Serializable
    private data class SharePayload(
        val type: String,
        val shareReference: String,
        val webUrl: String? = null,
    )

    @Serializable
    private data class ResolvedPayload(
        val type: String,
        val session: SessionRecord,
    )

    @Serializable
    private data class BridgeErrorPayload(
        val type: String,
        val code: String? = null,
    )

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BRIDGE_CHANNEL = "bridge"
        private const val BRIDGE_HELLO_TYPE = "bridge_hello"
        private const val BRIDGE_ERROR_TYPE = "bridge_error"
        private val RESPONSE_TYPES =
            setOf("bridge_session_share", "bridge_session_share_resolved", "bridge_session_share_revoked")
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000L
        val defaultJson: Json = Json { ignoreUnknownKeys = true }
    }
}
