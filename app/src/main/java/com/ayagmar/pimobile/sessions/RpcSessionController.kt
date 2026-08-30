package com.ayagmar.pimobile.sessions

import android.util.Log
import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corenet.PiRpcConnection
import com.ayagmar.pimobile.corenet.PiRpcConnectionConfig
import com.ayagmar.pimobile.corenet.WebSocketTarget
import com.ayagmar.pimobile.corerpc.AbortBashCommand
import com.ayagmar.pimobile.corerpc.AbortCommand
import com.ayagmar.pimobile.corerpc.AbortRetryCommand
import com.ayagmar.pimobile.corerpc.AgentSettledEvent
import com.ayagmar.pimobile.corerpc.AgentStartEvent
import com.ayagmar.pimobile.corerpc.AutoRetryEndEvent
import com.ayagmar.pimobile.corerpc.AutoRetryStartEvent
import com.ayagmar.pimobile.corerpc.AvailableModel
import com.ayagmar.pimobile.corerpc.BashCommand
import com.ayagmar.pimobile.corerpc.BashResult
import com.ayagmar.pimobile.corerpc.CompactCommand
import com.ayagmar.pimobile.corerpc.CycleModelCommand
import com.ayagmar.pimobile.corerpc.CycleThinkingLevelCommand
import com.ayagmar.pimobile.corerpc.ExportHtmlCommand
import com.ayagmar.pimobile.corerpc.ExtensionUiResponseCommand
import com.ayagmar.pimobile.corerpc.FollowUpCommand
import com.ayagmar.pimobile.corerpc.ForkCommand
import com.ayagmar.pimobile.corerpc.GetAvailableModelsCommand
import com.ayagmar.pimobile.corerpc.GetCommandsCommand
import com.ayagmar.pimobile.corerpc.GetForkMessagesCommand
import com.ayagmar.pimobile.corerpc.GetLastAssistantTextCommand
import com.ayagmar.pimobile.corerpc.GetSessionStatsCommand
import com.ayagmar.pimobile.corerpc.ImagePayload
import com.ayagmar.pimobile.corerpc.NewSessionCommand
import com.ayagmar.pimobile.corerpc.PromptCommand
import com.ayagmar.pimobile.corerpc.RpcCommand
import com.ayagmar.pimobile.corerpc.RpcIncomingMessage
import com.ayagmar.pimobile.corerpc.RpcResponse
import com.ayagmar.pimobile.corerpc.SessionStats
import com.ayagmar.pimobile.corerpc.SetAutoCompactionCommand
import com.ayagmar.pimobile.corerpc.SetAutoRetryCommand
import com.ayagmar.pimobile.corerpc.SetFollowUpModeCommand
import com.ayagmar.pimobile.corerpc.SetModelCommand
import com.ayagmar.pimobile.corerpc.SetSessionNameCommand
import com.ayagmar.pimobile.corerpc.SetSteeringModeCommand
import com.ayagmar.pimobile.corerpc.SetThinkingLevelCommand
import com.ayagmar.pimobile.corerpc.SteerCommand
import com.ayagmar.pimobile.corerpc.SwitchSessionCommand
import com.ayagmar.pimobile.coresessions.SessionKey
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.hosts.HostProfile
import com.ayagmar.pimobile.perf.PerformanceMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Suppress("TooManyFunctions", "LargeClass")
class RpcSessionController(
    private val clientId: String,
    private val connectionFactory: () -> PiRpcConnection = { PiRpcConnection() },
    private val connectTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val requestTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val treeRequestTimeoutMs: Long = TREE_REQUEST_TIMEOUT_MS,
) : SessionController {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _rpcEvents = MutableSharedFlow<RpcIncomingMessage>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _isStreaming = MutableStateFlow(false)
    private val _isRetrying = MutableStateFlow(false)
    private val _sessionChanged = MutableSharedFlow<String?>(extraBufferCapacity = 16)
    private val _activeSession = MutableStateFlow<ActiveSessionState?>(null)
    private val _timelineInvalidated = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private val _syncMetrics = MutableStateFlow(SessionSyncMetrics())
    private val entryProjection = SessionEntryProjection()
    private val activeTreeCache = ConcurrentHashMap<String, SessionTreeSnapshot>()

    private var projectedMessagesResponse: RpcResponse? = null
    private var activeConnection: PiRpcConnection? = null
    private var activeContext: ActiveConnectionContext? = null
    private var transportPreference: TransportPreference = TransportPreference.AUTO
    private var rpcEventsJob: Job? = null
    private var connectionStateJob: Job? = null
    private var streamingMonitorJob: Job? = null
    private var resyncMonitorJob: Job? = null
    private var invalidationMonitorJob: Job? = null
    private var reconnectRecoveryJob: Job? = null

    override val rpcEvents: SharedFlow<RpcIncomingMessage> = _rpcEvents
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    override val isRetrying: StateFlow<Boolean> = _isRetrying.asStateFlow()
    override val sessionChanged: SharedFlow<String?> = _sessionChanged
    override val activeSession: StateFlow<ActiveSessionState?> = _activeSession.asStateFlow()
    override val timelineInvalidated: SharedFlow<Unit> = _timelineInvalidated
    override val syncMetrics: StateFlow<SessionSyncMetrics> = _syncMetrics.asStateFlow()

    override fun setTransportPreference(preference: TransportPreference) {
        transportPreference = preference
    }

    override fun recordSafetyPoll() {
        _syncMetrics.value = _syncMetrics.value.copy(safetyPolls = _syncMetrics.value.safetyPolls + 1)
    }

    override fun getTransportPreference(): TransportPreference = transportPreference

    override fun getEffectiveTransportPreference(): TransportPreference {
        return resolveEffectiveTransport(transportPreference)
    }

    override fun getActiveCwd(): String? = activeContext?.cwd

    override suspend fun ensureConnected(
        hostProfile: HostProfile,
        token: String,
        cwd: String,
    ): Result<Unit> {
        val startedAt = System.currentTimeMillis()
        return mutex.withLock {
            runCatching {
                ensureConnectionLocked(
                    hostProfile = hostProfile,
                    token = token,
                    cwd = cwd,
                )
                runCatching {
                    PerformanceMetrics.recordOperation(
                        operation = "bridge_handshake_control",
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
                Unit
            }
        }
    }

    override suspend fun disconnect(): Result<Unit> {
        return mutex.withLock {
            runCatching {
                clearActiveConnection()
                Unit
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod")
    override suspend fun resume(
        hostProfile: HostProfile,
        token: String,
        session: SessionRecord,
    ): Result<String?> {
        val resumeStartedAt = System.currentTimeMillis()
        val previousIdentity = _activeSession.value
        val nextGeneration = (previousIdentity?.generation ?: 0L) + 1L
        _activeSession.value =
            ActiveSessionState(
                sessionPath = session.sessionPath.takeIf { it.isNotBlank() },
                generation = nextGeneration,
                isSwitching = true,
            )

        return mutex.withLock {
            try {
                val connection =
                    ensureConnectionLocked(
                        hostProfile = hostProfile,
                        token = token,
                        cwd = session.cwd,
                    )
                runCatching {
                    PerformanceMetrics.recordOperation(
                        operation = "resume_control_ready",
                        durationMs = System.currentTimeMillis() - resumeStartedAt,
                    )
                }

                if (session.sessionPath.isNotBlank()) {
                    val switchResponse =
                        sendAndAwaitResponse(
                            connection = connection,
                            requestTimeoutMs = requestTimeoutMs,
                            command =
                                SwitchSessionCommand(
                                    id = UUID.randomUUID().toString(),
                                    sessionPath = session.sessionPath,
                                ),
                            expectedCommand = SWITCH_SESSION_COMMAND,
                        ).requireSuccess("无法恢复所选会话")

                    switchResponse.requireNotCancelled("会话切换已取消")
                    runCatching {
                        PerformanceMetrics.recordOperation(
                            operation = "session_switch_response",
                            durationMs = System.currentTimeMillis() - resumeStartedAt,
                        )
                    }
                }

                val state = awaitResumedState(connection, session.sessionId.takeIf { session.hasStableIdentity })
                val newPath = state.data.stringField("sessionFile")
                val actualSessionId = state.data.stringField("sessionId")
                if (session.hasStableIdentity) {
                    check(actualSessionId == session.sessionId) {
                        "恢复后的会话标识与所选会话不一致"
                    }
                }
                resetSessionProjection()
                _activeSession.value =
                    ActiveSessionState(
                        sessionPath = newPath,
                        generation = nextGeneration,
                        isSwitching = false,
                        sessionKey =
                            session.sessionId
                                ?.takeIf { session.hasStableIdentity }
                                ?.let { id -> SessionKey(hostProfileId = hostProfile.id, sessionId = id) },
                    )
                _sessionChanged.emit(newPath)
                Result.success(newPath)
            } catch (error: Throwable) {
                _activeSession.value = previousIdentity?.copy(isSwitching = false)
                previousIdentity?.let { _sessionChanged.emit(it.sessionPath) }
                Result.failure(error)
            }
        }
    }

    private suspend fun awaitResumedState(
        connection: PiRpcConnection,
        expectedSessionId: String?,
    ): RpcResponse {
        var state = connection.requestState().requireSuccess("无法验证恢复后的会话")

        if (expectedSessionId != null) {
            repeat(SESSION_IDENTITY_RETRY_COUNT - 1) {
                if (state.data.stringField("sessionId") == expectedSessionId) return state
                delay(SESSION_IDENTITY_RETRY_DELAY_MS)
                state = connection.requestState().requireSuccess("无法验证恢复后的会话")
            }
        }
        return state
    }

    override suspend fun bootstrap(onStateAvailable: (RpcResponse) -> Unit): Result<SessionBootstrapSnapshot> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val stateResponse = connection.requestState().requireSuccess("无法加载状态")
                onStateAvailable(stateResponse)
                val messagesResponse =
                    projectedMessagesResponse
                        ?: connection.requestMessages().requireSuccess("无法加载消息")
                SessionBootstrapSnapshot(
                    stateResponse = stateResponse,
                    messagesResponse = messagesResponse,
                )
            }
        }
    }

    override suspend fun getMessages(): Result<RpcResponse> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                projectedMessagesResponse
                    ?: connection.requestMessages().requireSuccess("无法加载消息")
            }
        }
    }

    override suspend fun getState(): Result<RpcResponse> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                connection.requestState().requireSuccess("无法加载状态")
            }
        }
    }

    override suspend fun reloadActiveSessionFromDisk(): Result<String?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val sessionPath = refreshCurrentSessionPath(connection)
                check(!sessionPath.isNullOrBlank()) {
                    "没有可重新加载的活动会话文件"
                }

                val switchResponse =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = SwitchSessionCommand(id = UUID.randomUUID().toString(), sessionPath = sessionPath),
                        expectedCommand = SWITCH_SESSION_COMMAND,
                    ).requireSuccess("无法重新加载活动会话")

                switchResponse.requireNotCancelled("重新加载活动会话已取消")

                refreshCurrentSessionPath(connection)
            }
        }
    }

    override suspend fun renameSession(name: String): Result<String?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command = SetSessionNameCommand(id = UUID.randomUUID().toString(), name = name),
                    expectedCommand = SET_SESSION_NAME_COMMAND,
                ).requireSuccess("无法重命名会话")

                refreshCurrentSessionPath(connection)
            }
        }
    }

    override suspend fun compactSession(): Result<String?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command = CompactCommand(id = UUID.randomUUID().toString()),
                    expectedCommand = COMPACT_COMMAND,
                ).requireSuccess("无法压缩会话上下文")

                refreshCurrentSessionPath(connection)
            }
        }
    }

    override suspend fun exportSession(): Result<String> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = ExportHtmlCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = EXPORT_HTML_COMMAND,
                    ).requireSuccess("导出会话失败")

                response.data.stringField("path") ?: error("导出成功但未返回输出路径")
            }
        }
    }

    override suspend fun forkSessionFromEntryId(entryId: String): Result<String?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                forkWithEntryId(connection, entryId)
            }
        }
    }

    override suspend fun getForkMessages(): Result<List<ForkableMessage>> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = GetForkMessagesCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = GET_FORK_MESSAGES_COMMAND,
                    ).requireSuccess("加载分叉消息失败")

                parseForkableMessages(response.data)
            }
        }
    }

    override fun getCachedSessionTree(
        sessionPath: String,
        filter: String?,
    ): SessionTreeSnapshot? = activeTreeCache[treeCacheKey(sessionPath, filter)]

    override suspend fun getSessionTree(
        sessionPath: String?,
        filter: String?,
    ): Result<SessionTreeSnapshot> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val activeSessionPath = refreshCurrentSessionPath(connection)
                if (sessionPath.isNullOrBlank() || sessionPath == activeSessionPath) {
                    val response =
                        connection.requestTree(treeRequestTimeoutMs)
                            .requireSuccess("加载当前会话树失败")
                    val snapshot =
                        parseRpcSessionTreeSnapshot(
                            data = response.data,
                            sessionPath = activeSessionPath.orEmpty(),
                            filter = filter,
                        )
                    activeTreeCache[treeCacheKey(snapshot.sessionPath, filter)] = snapshot
                    return@runCatching snapshot
                }

                val bridgePayload =
                    buildJsonObject {
                        put("type", BRIDGE_GET_SESSION_TREE_TYPE)
                        if (!sessionPath.isNullOrBlank()) {
                            put("sessionPath", sessionPath)
                        }
                        if (!filter.isNullOrBlank()) {
                            put("filter", filter)
                        }
                    }

                val bridgeResponse = connection.requestBridge(bridgePayload, BRIDGE_SESSION_TREE_TYPE)
                parseSessionTreeSnapshot(bridgeResponse.payload)
            }
        }
    }

    override suspend fun getSessionFreshness(sessionPath: String): Result<SessionFreshnessSnapshot> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val bridgePayload =
                    buildJsonObject {
                        put("type", BRIDGE_GET_SESSION_FRESHNESS_TYPE)
                        put("sessionPath", sessionPath)
                    }

                val bridgeResponse =
                    connection.requestBridge(
                        payload = bridgePayload,
                        expectedType = BRIDGE_SESSION_FRESHNESS_TYPE,
                    )

                parseSessionFreshnessSnapshot(bridgeResponse.payload)
            }
        }
    }

    override suspend fun navigateTreeToEntry(entryId: String): Result<TreeNavigationResult> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val bridgePayload =
                    buildJsonObject {
                        put("type", BRIDGE_NAVIGATE_TREE_TYPE)
                        put("entryId", entryId)
                    }

                val bridgeResponse =
                    connection.requestBridge(
                        payload = bridgePayload,
                        expectedType = BRIDGE_TREE_NAVIGATION_RESULT_TYPE,
                    )

                parseTreeNavigationResult(bridgeResponse.payload)
            }
        }
    }

    private fun treeCacheKey(
        sessionPath: String,
        filter: String?,
    ): String = "$sessionPath:${filter.orEmpty()}"

    private fun markActiveTreesStale() {
        activeTreeCache.replaceAll { _, snapshot -> snapshot.copy(isStale = true) }
    }

    private suspend fun publishSessionChanged(sessionPath: String?) {
        _activeSession.value =
            ActiveSessionState(
                sessionPath = sessionPath,
                generation = (_activeSession.value?.generation ?: 0L) + 1L,
                isSwitching = false,
            )
        _sessionChanged.emit(sessionPath)
    }

    private suspend fun forkWithEntryId(
        connection: PiRpcConnection,
        entryId: String,
    ): String? {
        val forkResponse =
            sendAndAwaitResponse(
                connection = connection,
                requestTimeoutMs = requestTimeoutMs,
                command =
                    ForkCommand(
                        id = UUID.randomUUID().toString(),
                        entryId = entryId,
                    ),
                expectedCommand = FORK_COMMAND,
            ).requireSuccess("创建分叉会话失败")

        val cancelled = forkResponse.data.booleanField("cancelled") ?: false
        check(!cancelled) {
            "已取消分叉"
        }

        val newPath = refreshCurrentSessionPath(connection)
        resetSessionProjection()
        publishSessionChanged(newPath)
        return newPath
    }

    override suspend fun sendPrompt(
        message: String,
        images: List<ImagePayload>,
        expectedSessionKey: SessionKey?,
    ): Result<Unit> {
        return mutex.withLock {
            runCatching {
                requireExpectedActiveSession(expectedSessionKey)
                val connection = ensureActiveConnection()
                val isCurrentlyStreaming = _isStreaming.value
                val command =
                    PromptCommand(
                        id = UUID.randomUUID().toString(),
                        message = message,
                        images = images,
                        streamingBehavior = if (isCurrentlyStreaming) "steer" else null,
                    )

                val shouldMarkStreaming = !isCurrentlyStreaming
                if (shouldMarkStreaming) {
                    _isStreaming.value = true
                }

                runCatching {
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = command,
                        expectedCommand = PROMPT_COMMAND,
                    ).requireSuccess("无法发送消息")
                    Unit
                }.onFailure {
                    if (shouldMarkStreaming) {
                        _isStreaming.value = false
                    }
                }.getOrThrow()
            }
        }
    }

    override suspend fun abort(): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command = AbortCommand(id = UUID.randomUUID().toString()),
                    expectedCommand = ABORT_COMMAND,
                ).requireSuccess("无法中止运行")
                Unit
            }
        }
    }

    override suspend fun steer(
        message: String,
        expectedSessionKey: SessionKey?,
    ): Result<Unit> {
        return mutex.withLock {
            runCatching {
                requireExpectedActiveSession(expectedSessionKey)
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command =
                        SteerCommand(
                            id = UUID.randomUUID().toString(),
                            message = message,
                        ),
                    expectedCommand = STEER_COMMAND,
                ).requireSuccess("无法发送调整方向消息")
                Unit
            }
        }
    }

    override suspend fun followUp(
        message: String,
        expectedSessionKey: SessionKey?,
    ): Result<Unit> {
        return mutex.withLock {
            runCatching {
                requireExpectedActiveSession(expectedSessionKey)
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command =
                        FollowUpCommand(
                            id = UUID.randomUUID().toString(),
                            message = message,
                        ),
                    expectedCommand = FOLLOW_UP_COMMAND,
                ).requireSuccess("无法将追加消息加入队列")
                Unit
            }
        }
    }

    override suspend fun cycleModel(): Result<ModelInfo?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = CycleModelCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = CYCLE_MODEL_COMMAND,
                    ).requireSuccess("切换模型失败")

                parseModelInfo(response.data)
            }
        }
    }

    override suspend fun cycleThinkingLevel(): Result<String?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = CycleThinkingLevelCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = CYCLE_THINKING_COMMAND,
                    ).requireSuccess("切换思考级别失败")

                response.data?.stringField("level")
            }
        }
    }

    override suspend fun setThinkingLevel(level: String): Result<String?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = SetThinkingLevelCommand(id = UUID.randomUUID().toString(), level = level),
                        expectedCommand = SET_THINKING_LEVEL_COMMAND,
                    ).requireSuccess("设置思考级别失败")

                response.data?.stringField("level") ?: level
            }
        }
    }

    override suspend fun abortRetry(): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command = AbortRetryCommand(id = UUID.randomUUID().toString()),
                    expectedCommand = ABORT_RETRY_COMMAND,
                ).requireSuccess("中止重试失败")
                Unit
            }
        }
    }

    override suspend fun sendExtensionUiResponse(
        requestId: String,
        value: String?,
        confirmed: Boolean?,
        cancelled: Boolean?,
    ): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val command =
                    ExtensionUiResponseCommand(
                        id = requestId,
                        value = value,
                        confirmed = confirmed,
                        cancelled = cancelled,
                    )
                connection.sendCommand(command)
            }
        }
    }

    override suspend fun newSession(): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val newSessionResponse =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = NewSessionCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = NEW_SESSION_COMMAND,
                    ).requireSuccess("无法新建会话")

                newSessionResponse.requireNotCancelled("新建会话已取消")

                val newPath = refreshCurrentSessionPath(connection)
                resetSessionProjection()
                publishSessionChanged(newPath)
                Unit
            }
        }
    }

    override suspend fun getCommands(): Result<List<SlashCommandInfo>> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = GetCommandsCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = GET_COMMANDS_COMMAND,
                    ).requireSuccess("加载命令列表失败")

                parseSlashCommands(response.data)
            }
        }
    }

    override suspend fun getClientId(): String {
        return clientId
    }

    override suspend fun getLastAssistantText(): Result<String?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = GetLastAssistantTextCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = GET_LAST_ASSISTANT_TEXT_COMMAND,
                    ).requireSuccess("读取最新助手回复失败")

                response.data.stringField("text")
            }
        }
    }

    override suspend fun importSessionJsonl(
        fileName: String,
        jsonlContent: String,
    ): Result<String?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val bridgePayload =
                    buildJsonObject {
                        put("type", BRIDGE_IMPORT_SESSION_JSONL_TYPE)
                        put("fileName", fileName)
                        put("content", jsonlContent)
                    }

                val bridgeResponse =
                    connection.requestBridge(
                        payload = bridgePayload,
                        expectedType = BRIDGE_SESSION_IMPORTED_TYPE,
                    )

                val sessionPath = bridgeResponse.payload.stringField("sessionPath")
                resetSessionProjection()
                publishSessionChanged(sessionPath)
                sessionPath
            }
        }
    }

    override suspend fun executeBash(
        command: String,
        timeoutMs: Int?,
    ): Result<BashResult> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val bashCommand =
                    BashCommand(
                        id = UUID.randomUUID().toString(),
                        command = command,
                        timeoutMs = timeoutMs,
                    )
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = timeoutMs?.toLong() ?: BASH_TIMEOUT_MS,
                        command = bashCommand,
                        expectedCommand = BASH_COMMAND,
                    ).requireSuccess("执行 bash 命令失败")

                parseBashResult(response.data)
            }
        }
    }

    override suspend fun abortBash(): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command = AbortBashCommand(id = UUID.randomUUID().toString()),
                    expectedCommand = ABORT_BASH_COMMAND,
                ).requireSuccess("中止 bash 命令失败")
                Unit
            }
        }
    }

    override suspend fun getSessionStats(): Result<SessionStats> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = GetSessionStatsCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = GET_SESSION_STATS_COMMAND,
                    ).requireSuccess("获取会话统计失败")

                parseSessionStats(response.data)
            }
        }
    }

    override suspend fun getAvailableModels(): Result<List<AvailableModel>> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command = GetAvailableModelsCommand(id = UUID.randomUUID().toString()),
                        expectedCommand = GET_AVAILABLE_MODELS_COMMAND,
                    ).requireSuccess("获取可用模型失败")

                parseAvailableModels(response.data)
            }
        }
    }

    override suspend fun setModel(
        provider: String,
        modelId: String,
    ): Result<ModelInfo?> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                val response =
                    sendAndAwaitResponse(
                        connection = connection,
                        requestTimeoutMs = requestTimeoutMs,
                        command =
                            SetModelCommand(
                                id = UUID.randomUUID().toString(),
                                provider = provider,
                                modelId = modelId,
                            ),
                        expectedCommand = SET_MODEL_COMMAND,
                    ).requireSuccess("设置模型失败")

                // set_model returns the model object directly (without thinkingLevel).
                // Refresh state to get the effective thinking level.
                val refreshedState = connection.requestState().requireSuccess("设置模型后刷新状态失败")
                parseModelInfo(refreshedState.data) ?: parseModelInfo(response.data)
            }
        }
    }

    override suspend fun setAutoCompaction(enabled: Boolean): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command =
                        SetAutoCompactionCommand(
                            id = UUID.randomUUID().toString(),
                            enabled = enabled,
                        ),
                    expectedCommand = SET_AUTO_COMPACTION_COMMAND,
                ).requireSuccess("设置自动压缩失败")
                Unit
            }
        }
    }

    override suspend fun setAutoRetry(enabled: Boolean): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command =
                        SetAutoRetryCommand(
                            id = UUID.randomUUID().toString(),
                            enabled = enabled,
                        ),
                    expectedCommand = SET_AUTO_RETRY_COMMAND,
                ).requireSuccess("设置自动重试失败")
                Unit
            }
        }
    }

    override suspend fun setSteeringMode(mode: String): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command =
                        SetSteeringModeCommand(
                            id = UUID.randomUUID().toString(),
                            mode = mode,
                        ),
                    expectedCommand = SET_STEERING_MODE_COMMAND,
                ).requireSuccess("设置引导模式失败")
                Unit
            }
        }
    }

    override suspend fun setFollowUpMode(mode: String): Result<Unit> {
        return mutex.withLock {
            runCatching {
                val connection = ensureActiveConnection()
                sendAndAwaitResponse(
                    connection = connection,
                    requestTimeoutMs = requestTimeoutMs,
                    command =
                        SetFollowUpModeCommand(
                            id = UUID.randomUUID().toString(),
                            mode = mode,
                        ),
                    expectedCommand = SET_FOLLOW_UP_MODE_COMMAND,
                ).requireSuccess("设置追问模式失败")
                Unit
            }
        }
    }

    private suspend fun ensureConnectionLocked(
        hostProfile: HostProfile,
        token: String,
        cwd: String,
    ): PiRpcConnection {
        val normalizedCwd = cwd.trim()
        require(normalizedCwd.isNotBlank()) { "cwd 不能为空" }

        val currentConnection = activeConnection
        val currentContext = activeContext
        val shouldReuse =
            currentConnection != null &&
                currentContext != null &&
                currentContext.matches(hostProfile = hostProfile, token = token, cwd = normalizedCwd) &&
                _connectionState.value != ConnectionState.DISCONNECTED

        if (shouldReuse) {
            return requireNotNull(currentConnection)
        }

        clearActiveConnection(resetContext = false)

        val nextConnection = connectionFactory()
        val endpoint = resolveEndpointForTransport(hostProfile)
        val config =
            PiRpcConnectionConfig(
                target =
                    WebSocketTarget(
                        url = endpoint,
                        headers = mapOf(AUTHORIZATION_HEADER to "Bearer $token"),
                        connectTimeoutMs = connectTimeoutMs,
                    ),
                cwd = normalizedCwd,
                clientId = clientId,
                connectTimeoutMs = connectTimeoutMs,
                requestTimeoutMs = requestTimeoutMs,
            )

        runCatching {
            nextConnection.connect(config)
        }.onFailure {
            runCatching { nextConnection.disconnect() }
        }.getOrThrow()

        activeConnection = nextConnection
        activeContext =
            ActiveConnectionContext(
                endpoint = hostProfile.endpoint,
                token = token,
                cwd = normalizedCwd,
            )
        observeConnection(nextConnection)
        return nextConnection
    }

    private fun resolveEndpointForTransport(hostProfile: HostProfile): String {
        val effectiveTransport = resolveEffectiveTransport(transportPreference)

        if (transportPreference == TransportPreference.SSE && effectiveTransport == TransportPreference.WEBSOCKET) {
            Log.i(
                TRANSPORT_LOG_TAG,
                "SSE transport requested but bridge currently supports WebSocket only; using WebSocket fallback",
            )
        }

        return when (effectiveTransport) {
            TransportPreference.WEBSOCKET,
            TransportPreference.AUTO,
            TransportPreference.SSE,
            -> hostProfile.endpoint
        }
    }

    private fun resolveEffectiveTransport(requested: TransportPreference): TransportPreference {
        return when (requested) {
            TransportPreference.AUTO,
            TransportPreference.WEBSOCKET,
            TransportPreference.SSE,
            -> TransportPreference.WEBSOCKET
        }
    }

    private suspend fun clearActiveConnection(resetContext: Boolean = true) {
        rpcEventsJob?.cancel()
        connectionStateJob?.cancel()
        streamingMonitorJob?.cancel()
        resyncMonitorJob?.cancel()
        invalidationMonitorJob?.cancel()
        reconnectRecoveryJob?.cancel()
        rpcEventsJob = null
        connectionStateJob = null
        streamingMonitorJob = null
        resyncMonitorJob = null
        invalidationMonitorJob = null
        reconnectRecoveryJob = null

        activeConnection?.disconnect()
        activeConnection = null
        if (resetContext) {
            activeContext = null
        }
        _connectionState.value = ConnectionState.DISCONNECTED
        _isStreaming.value = false
        _isRetrying.value = false
    }

    private fun observeConnection(connection: PiRpcConnection) {
        rpcEventsJob?.cancel()
        connectionStateJob?.cancel()
        streamingMonitorJob?.cancel()
        resyncMonitorJob?.cancel()
        invalidationMonitorJob?.cancel()
        reconnectRecoveryJob?.cancel()
        reconnectRecoveryJob = null

        rpcEventsJob =
            scope.launch {
                connection.rpcEvents.collect { event ->
                    _rpcEvents.emit(event)
                }
            }

        connectionStateJob =
            scope.launch {
                connection.connectionState.collect { state ->
                    when (state) {
                        ConnectionState.DISCONNECTED -> {
                            if (activeConnection === connection && activeContext != null) {
                                _connectionState.value = ConnectionState.RECONNECTING
                                scheduleReconnectRecovery(connection)
                            } else {
                                cancelReconnectRecovery()
                                _connectionState.value = ConnectionState.DISCONNECTED
                                _isStreaming.value = false
                            }
                        }

                        ConnectionState.CONNECTED -> {
                            cancelReconnectRecovery()
                            _connectionState.value = ConnectionState.CONNECTED
                        }

                        ConnectionState.CONNECTING,
                        ConnectionState.RECONNECTING,
                        -> {
                            _connectionState.value = state
                        }
                    }
                }
            }

        streamingMonitorJob =
            scope.launch {
                connection.rpcEvents.collect { event ->
                    when (event) {
                        is AgentStartEvent -> _isStreaming.value = true
                        is AgentSettledEvent -> {
                            _isStreaming.value = false
                            _isRetrying.value = false
                        }
                        is AutoRetryStartEvent -> _isRetrying.value = true
                        is AutoRetryEndEvent -> _isRetrying.value = false

                        else -> Unit
                    }
                }
            }

        resyncMonitorJob = observeEntryResync(connection)
        invalidationMonitorJob = observeSessionInvalidations(connection)
    }

    private fun resetSessionProjection() {
        entryProjection.reset()
        projectedMessagesResponse = null
    }

    private fun observeEntryResync(connection: PiRpcConnection): Job {
        return scope.launch {
            connection.resyncEvents.collect { snapshot ->
                val isStreaming = snapshot.stateResponse.data.booleanField("isStreaming") ?: false
                _isStreaming.value = isStreaming
                applyEntrySnapshot(connection, snapshot.entriesResponse, snapshot.fullRebuild)
            }
        }
    }

    private suspend fun applyEntrySnapshot(
        connection: PiRpcConnection,
        response: RpcResponse,
        fullRebuild: Boolean,
    ) {
        val entryCount = runCatching { response.data?.get("entries")?.jsonArray?.size }.getOrNull() ?: 0
        val update = entryProjection.apply(response.data, fullRebuild)
        if (update is ProjectionUpdate.Applied) {
            projectedMessagesResponse = update.toMessagesResponse()
            _syncMetrics.value =
                _syncMetrics.value.copy(
                    fullRebuilds = _syncMetrics.value.fullRebuilds + if (fullRebuild) 1 else 0,
                    incrementalEntries = _syncMetrics.value.incrementalEntries + if (fullRebuild) 0 else entryCount,
                )
            _timelineInvalidated.emit(Unit)
            return
        }

        val rebuildResponse = connection.requestEntries().requireSuccess("重建会话条目失败")
        val rebuilt = entryProjection.apply(rebuildResponse.data, fullRebuild = true)
        _syncMetrics.value = _syncMetrics.value.copy(fullRebuilds = _syncMetrics.value.fullRebuilds + 1)
        projectedMessagesResponse =
            if (rebuilt is ProjectionUpdate.Applied) {
                rebuilt.toMessagesResponse()
            } else {
                connection.requestMessages().requireSuccess("重建会话时间线失败")
            }
        _timelineInvalidated.emit(Unit)
    }

    private fun ProjectionUpdate.Applied.toMessagesResponse(): RpcResponse {
        return RpcResponse(
            type = "response",
            command = "get_messages",
            success = true,
            data = buildJsonObject { put("messages", messages) },
        )
    }

    private fun observeSessionInvalidations(connection: PiRpcConnection): Job {
        return scope.launch {
            connection.bridgeEvents.collect { event ->
                if (event.type == BRIDGE_SESSION_INVALIDATED_TYPE) {
                    markActiveTreesStale()
                    runCatching { connection.resync() }
                        .onFailure { error ->
                            Log.w(
                                TRANSPORT_LOG_TAG,
                                "Session invalidation resync failed: ${error.message ?: "unknown"}",
                            )
                        }
                }
            }
        }
    }

    private fun scheduleReconnectRecovery(connection: PiRpcConnection) {
        if (reconnectRecoveryJob?.isActive == true) {
            return
        }

        reconnectRecoveryJob =
            scope.launch {
                delay(DISCONNECT_RECOVERY_DELAY_MS)

                if (activeConnection !== connection || activeContext == null) {
                    return@launch
                }

                // Clear job reference before reconnect to avoid cancelling this coroutine
                // via the CONNECTED state observer while reconnect() is in-flight.
                reconnectRecoveryJob = null

                runCatching {
                    connection.reconnect()
                }.onFailure { error ->
                    Log.w(
                        TRANSPORT_LOG_TAG,
                        "Automatic reconnect after disconnect failed: ${error.message ?: "unknown"}",
                    )
                    if (activeConnection === connection) {
                        _connectionState.value = ConnectionState.DISCONNECTED
                        _isStreaming.value = false
                    }
                }
            }
    }

    private fun cancelReconnectRecovery() {
        reconnectRecoveryJob?.cancel()
        reconnectRecoveryJob = null
    }

    private fun requireExpectedActiveSession(expectedSessionKey: SessionKey?) {
        if (expectedSessionKey == null) return
        val active = _activeSession.value
        check(active?.sessionKey == expectedSessionKey && !active.isSwitching) {
            "发送快捷回复前活动会话已切换，请重试"
        }
    }

    private fun ensureActiveConnection(): PiRpcConnection {
        return requireNotNull(activeConnection) {
            "没有活动会话，请先恢复一个会话。"
        }
    }

    private suspend fun refreshCurrentSessionPath(connection: PiRpcConnection): String? {
        val stateResponse = connection.requestState().requireSuccess("读取连接状态失败")
        return stateResponse.data.stringField("sessionFile")
    }

    private data class ActiveConnectionContext(
        val endpoint: String,
        val token: String,
        val cwd: String,
    ) {
        fun matches(
            hostProfile: HostProfile,
            token: String,
            cwd: String,
        ): Boolean {
            return endpoint == hostProfile.endpoint && this.token == token && this.cwd == cwd
        }
    }

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val PROMPT_COMMAND = "prompt"
        private const val ABORT_COMMAND = "abort"
        private const val STEER_COMMAND = "steer"
        private const val FOLLOW_UP_COMMAND = "follow_up"
        private const val SWITCH_SESSION_COMMAND = "switch_session"
        private const val SET_SESSION_NAME_COMMAND = "set_session_name"
        private const val COMPACT_COMMAND = "compact"
        private const val EXPORT_HTML_COMMAND = "export_html"
        private const val GET_FORK_MESSAGES_COMMAND = "get_fork_messages"
        private const val FORK_COMMAND = "fork"
        private const val CYCLE_MODEL_COMMAND = "cycle_model"
        private const val CYCLE_THINKING_COMMAND = "cycle_thinking_level"
        private const val SET_THINKING_LEVEL_COMMAND = "set_thinking_level"
        private const val ABORT_RETRY_COMMAND = "abort_retry"
        private const val NEW_SESSION_COMMAND = "new_session"
        private const val GET_COMMANDS_COMMAND = "get_commands"
        private const val GET_LAST_ASSISTANT_TEXT_COMMAND = "get_last_assistant_text"
        private const val BASH_COMMAND = "bash"
        private const val ABORT_BASH_COMMAND = "abort_bash"
        private const val GET_SESSION_STATS_COMMAND = "get_session_stats"
        private const val GET_AVAILABLE_MODELS_COMMAND = "get_available_models"
        private const val SET_MODEL_COMMAND = "set_model"
        private const val SET_AUTO_COMPACTION_COMMAND = "set_auto_compaction"
        private const val SET_AUTO_RETRY_COMMAND = "set_auto_retry"
        private const val SET_STEERING_MODE_COMMAND = "set_steering_mode"
        private const val SET_FOLLOW_UP_MODE_COMMAND = "set_follow_up_mode"
        private const val TREE_REQUEST_TIMEOUT_MS = 30_000L
        private const val BRIDGE_GET_SESSION_TREE_TYPE = "bridge_get_session_tree"
        private const val BRIDGE_SESSION_TREE_TYPE = "bridge_session_tree"
        private const val BRIDGE_GET_SESSION_FRESHNESS_TYPE = "bridge_get_session_freshness"
        private const val BRIDGE_SESSION_FRESHNESS_TYPE = "bridge_session_freshness"
        private const val BRIDGE_IMPORT_SESSION_JSONL_TYPE = "bridge_import_session_jsonl"
        private const val BRIDGE_SESSION_IMPORTED_TYPE = "bridge_session_imported"
        private const val BRIDGE_NAVIGATE_TREE_TYPE = "bridge_navigate_tree"
        private const val BRIDGE_TREE_NAVIGATION_RESULT_TYPE = "bridge_tree_navigation_result"
        private const val BRIDGE_SESSION_INVALIDATED_TYPE = "bridge_session_invalidated"
        private const val EVENT_BUFFER_CAPACITY = 256
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val SESSION_IDENTITY_RETRY_COUNT = 5
        private const val SESSION_IDENTITY_RETRY_DELAY_MS = 200L
        private const val BASH_TIMEOUT_MS = 60_000L
        private const val DISCONNECT_RECOVERY_DELAY_MS = 700L
        private const val TRANSPORT_LOG_TAG = "RpcTransport"
    }
}

private suspend fun sendAndAwaitResponse(
    connection: PiRpcConnection,
    requestTimeoutMs: Long,
    command: RpcCommand,
    expectedCommand: String,
): RpcResponse {
    val commandId = requireNotNull(command.id) { "RPC command id is required" }

    return coroutineScope {
        val responseDeferred =
            async {
                connection.rpcEvents
                    .filterIsInstance<RpcResponse>()
                    .first { response ->
                        response.id == commandId && response.command == expectedCommand
                    }
            }

        connection.sendCommand(command)

        withTimeout(requestTimeoutMs) {
            responseDeferred.await()
        }
    }
}

private fun RpcResponse.requireSuccess(defaultError: String): RpcResponse {
    check(success) {
        error ?: defaultError
    }

    return this
}

private fun RpcResponse.requireNotCancelled(defaultError: String): RpcResponse {
    check(data.booleanField("cancelled") != true) {
        defaultError
    }

    return this
}

private fun JsonObject?.stringField(fieldName: String): String? {
    return runCatching { this?.get(fieldName)?.jsonPrimitive?.contentOrNull }.getOrNull()
}

private fun JsonObject?.booleanField(fieldName: String): Boolean? {
    return runCatching { this?.get(fieldName)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }.getOrNull()
}
