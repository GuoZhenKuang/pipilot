package com.ayagmar.pimobile.corenet

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PiRpcConnectionTest {
    @Test
    fun `connect initializes bridge context and performs initial resync`() =
        runBlocking {
            val transport = FakeSocketTransport()
            transport.onSend = { outgoing -> transport.respondToOutgoing(outgoing) }
            val connection = PiRpcConnection(transport = transport)

            connection.connect(
                PiRpcConnectionConfig(
                    target = WebSocketTarget(url = "ws://127.0.0.1:3000/ws"),
                    cwd = "/tmp/project-a",
                    sessionPath = "/tmp/session-a.jsonl",
                    clientId = "client-a",
                ),
            )

            val sentTypes = transport.sentPayloadTypes()
            assertEquals(
                listOf("bridge_set_cwd", "bridge_acquire_control", "get_state", "get_entries"),
                sentTypes,
            )
            assertTrue(transport.connectedTarget?.url?.contains("clientId=client-a") == true)

            val snapshot = connection.resyncEvents.first()
            assertEquals("get_state", snapshot.stateResponse.command)
            assertEquals("get_entries", snapshot.entriesResponse.command)

            connection.disconnect()
        }

    @Test
    fun `disconnect emits bridge release control before transport shutdown`() =
        runBlocking {
            val transport = FakeSocketTransport()
            transport.onSend = { outgoing -> transport.respondToOutgoing(outgoing) }
            val connection = PiRpcConnection(transport = transport)

            connection.connect(
                PiRpcConnectionConfig(
                    target = WebSocketTarget(url = "ws://127.0.0.1:3000/ws"),
                    cwd = "/tmp/project-release",
                    sessionPath = "/tmp/session-release.jsonl",
                    clientId = "client-release",
                ),
            )

            transport.clearSentMessages()
            connection.disconnect()

            val sentTypes = transport.sentPayloadTypes()
            assertEquals(listOf("bridge_release_control"), sentTypes)

            val releasePayload = transport.sentPayloads().single()
            assertEquals("/tmp/project-release", releasePayload["cwd"]?.toString()?.trim('"'))
            assertEquals(
                "/tmp/session-release.jsonl",
                releasePayload["sessionPath"]?.toString()?.trim('"'),
            )
            assertEquals(ConnectionState.DISCONNECTED, transport.connectionState.value)
        }

    @Test
    fun `malformed rpc envelope does not stop subsequent requests`() =
        runBlocking {
            val transport = FakeSocketTransport()
            transport.onSend = { outgoing -> transport.respondToOutgoing(outgoing) }
            val connection = PiRpcConnection(transport = transport)

            connection.connect(
                PiRpcConnectionConfig(
                    target = WebSocketTarget(url = "ws://127.0.0.1:3000/ws"),
                    cwd = "/tmp/project-c",
                    clientId = "client-c",
                ),
            )

            transport.emitRawEnvelope(
                """{"channel":"rpc","payload":{}}""",
            )

            val stateResponse = withTimeout(2_000) { connection.requestState() }
            assertEquals("get_state", stateResponse.command)

            connection.disconnect()
        }

    @Test
    fun `reconnect transition triggers deterministic resync`() =
        runBlocking {
            val transport = FakeSocketTransport()
            transport.onSend = { outgoing -> transport.respondToOutgoing(outgoing) }
            val connection = PiRpcConnection(transport = transport)

            connection.connect(
                PiRpcConnectionConfig(
                    target = WebSocketTarget(url = "ws://127.0.0.1:3000/ws"),
                    cwd = "/tmp/project-b",
                    clientId = "client-b",
                ),
            )

            val reconnectSnapshotDeferred =
                async {
                    connection.resyncEvents.drop(1).first()
                }

            transport.clearSentMessages()
            transport.simulateReconnect(
                resumed = true,
                cwd = "/tmp/project-b",
            )

            val reconnectSnapshot = withTimeout(2_000) { reconnectSnapshotDeferred.await() }
            assertEquals("get_state", reconnectSnapshot.stateResponse.command)
            assertEquals("get_entries", reconnectSnapshot.entriesResponse.command)
            assertEquals(
                listOf("bridge_set_cwd", "bridge_acquire_control", "get_state", "get_entries"),
                transport.sentPayloadTypes(),
            )
            val entriesRequest = transport.sentPayloads().last()
            assertEquals("entry-1", entriesRequest["since"]?.toString()?.trim('"'))

            connection.disconnect()
        }

    @Test
    fun `unknown entry cursor performs exactly one full entries rebuild`() =
        runBlocking {
            val transport = FakeSocketTransport()
            transport.onSend = { outgoing -> transport.respondToOutgoing(outgoing) }
            val connection = PiRpcConnection(transport = transport)

            connection.connect(
                PiRpcConnectionConfig(
                    target = WebSocketTarget(url = "ws://127.0.0.1:3000/ws"),
                    cwd = "/tmp/project-cursor",
                    clientId = "client-cursor",
                ),
            )

            transport.clearSentMessages()
            transport.rejectNextEntriesCursor = true
            val snapshot = connection.resync()

            assertTrue(snapshot.fullRebuild)
            assertEquals(listOf("get_state", "get_entries", "get_entries"), transport.sentPayloadTypes())
            assertEquals("entry-1", transport.sentPayloads()[1]["since"]?.toString()?.trim('"'))
            assertTrue("since" !in transport.sentPayloads()[2])

            connection.disconnect()
        }

    @Test
    fun `in-flight request is cancelled when transport enters reconnecting`() =
        runBlocking {
            val transport = FakeSocketTransport()
            transport.onSend = { outgoing -> transport.respondToOutgoing(outgoing) }
            val connection = PiRpcConnection(transport = transport)

            connection.connect(
                PiRpcConnectionConfig(
                    target = WebSocketTarget(url = "ws://127.0.0.1:3000/ws"),
                    cwd = "/tmp/project-d",
                    clientId = "client-d",
                    requestTimeoutMs = 5_000,
                ),
            )

            transport.onSend = { outgoing -> transport.respondToBridgeControlOnly(outgoing) }

            val pendingRequest =
                async {
                    runCatching {
                        connection.requestState()
                    }
                }

            delay(20)
            transport.setConnectionState(ConnectionState.RECONNECTING)

            val requestResult = withTimeout(1_000) { pendingRequest.await() }
            assertTrue(requestResult.isFailure)

            connection.disconnect()
        }

    private class FakeSocketTransport : SocketTransport {
        private val json = Json { ignoreUnknownKeys = true }

        override val inboundMessages = MutableSharedFlow<String>(replay = 64, extraBufferCapacity = 64)
        override val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)

        val sentMessages = mutableListOf<String>()
        var connectedTarget: WebSocketTarget? = null
        var onSend: suspend (String) -> Unit = {}
        var rejectNextEntriesCursor = false

        override suspend fun connect(target: WebSocketTarget) {
            connectedTarget = target
            connectionState.value = ConnectionState.CONNECTING
            connectionState.value = ConnectionState.CONNECTED
            emitBridge(
                buildJsonObject {
                    put("type", "bridge_hello")
                    put("clientId", "fake-client")
                    put("resumed", false)
                    put("cwd", JsonNull)
                },
            )
        }

        override suspend fun reconnect() {
            connectionState.value = ConnectionState.RECONNECTING
            connectionState.value = ConnectionState.CONNECTED
        }

        override suspend fun disconnect() {
            connectionState.value = ConnectionState.DISCONNECTED
        }

        override suspend fun send(message: String) {
            sentMessages += message
            onSend(message)
        }

        suspend fun simulateReconnect(
            resumed: Boolean,
            cwd: String,
        ) {
            connectionState.value = ConnectionState.RECONNECTING
            delay(25)
            connectionState.value = ConnectionState.CONNECTED
            emitBridge(
                buildJsonObject {
                    put("type", "bridge_hello")
                    put("clientId", "fake-client")
                    put("resumed", resumed)
                    put("cwd", cwd)
                },
            )
        }

        fun setConnectionState(state: ConnectionState) {
            connectionState.value = state
        }

        fun clearSentMessages() {
            sentMessages.clear()
        }

        fun sentPayloadTypes(): List<String> {
            return sentMessages.mapNotNull { message ->
                val payload = parsePayload(message)
                payload["type"]?.let { type ->
                    type.toString().trim('"')
                }
            }
        }

        fun sentPayloads(): List<JsonObject> {
            return sentMessages.map(::parsePayload)
        }

        suspend fun emitRawEnvelope(raw: String) {
            inboundMessages.emit(raw)
        }

        suspend fun respondToOutgoing(message: String) {
            val envelope = json.parseToJsonElement(message).jsonObject
            val channel = envelope["channel"]?.toString()?.trim('"')
            val payload = parsePayload(message)

            if (channel == "bridge") {
                when (payload["type"]?.toString()?.trim('"')) {
                    "bridge_set_cwd" -> emitBridge(buildJsonObject { put("type", "bridge_cwd_set") })
                    "bridge_acquire_control" -> emitBridge(buildJsonObject { put("type", "bridge_control_acquired") })
                }
                return
            }

            val type = payload["type"]?.toString()?.trim('"')
            if (channel == "rpc" && type == "get_state") {
                emitRpcResponse(
                    id = payload["id"]?.toString()?.trim('"').orEmpty(),
                    command = "get_state",
                )
            }
            if (channel == "rpc" && type == "get_messages") {
                emitRpcResponse(
                    id = payload["id"]?.toString()?.trim('"').orEmpty(),
                    command = "get_messages",
                )
            }
            if (channel == "rpc" && type == "get_entries") {
                if (rejectNextEntriesCursor && "since" in payload) {
                    rejectNextEntriesCursor = false
                    emitRpc(
                        buildJsonObject {
                            put("id", payload["id"]?.toString()?.trim('"').orEmpty())
                            put("type", "response")
                            put("command", "get_entries")
                            put("success", false)
                            put("error", "Unknown entry cursor")
                        },
                    )
                    return
                }
                emitRpc(
                    buildJsonObject {
                        put("id", payload["id"]?.toString()?.trim('"').orEmpty())
                        put("type", "response")
                        put("command", "get_entries")
                        put("success", true)
                        put(
                            "data",
                            buildJsonObject {
                                put(
                                    "entries",
                                    kotlinx.serialization.json.buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "message")
                                                put("id", "entry-1")
                                                put("parentId", JsonNull)
                                            },
                                        )
                                    },
                                )
                                put("leafId", "entry-1")
                            },
                        )
                    },
                )
            }
        }

        suspend fun respondToBridgeControlOnly(message: String) {
            val envelope = json.parseToJsonElement(message).jsonObject
            val channel = envelope["channel"]?.toString()?.trim('"')
            if (channel != "bridge") {
                return
            }

            val payload = parsePayload(message)
            when (payload["type"]?.toString()?.trim('"')) {
                "bridge_set_cwd" -> emitBridge(buildJsonObject { put("type", "bridge_cwd_set") })
                "bridge_acquire_control" -> emitBridge(buildJsonObject { put("type", "bridge_control_acquired") })
            }
        }

        private suspend fun emitRpcResponse(
            id: String,
            command: String,
        ) {
            emitRpc(
                buildJsonObject {
                    put("id", id)
                    put("type", "response")
                    put("command", command)
                    put("success", true)
                    put("data", buildJsonObject {})
                },
            )
        }

        private suspend fun emitBridge(payload: JsonObject) {
            inboundMessages.emit(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("channel", "bridge")
                        put("payload", payload)
                    },
                ),
            )
        }

        private suspend fun emitRpc(payload: JsonObject) {
            inboundMessages.emit(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("channel", "rpc")
                        put("payload", payload)
                    },
                ),
            )
        }

        private fun parsePayload(message: String): JsonObject {
            return json.parseToJsonElement(message).jsonObject["payload"]?.jsonObject ?: JsonObject(emptyMap())
        }
    }
}
