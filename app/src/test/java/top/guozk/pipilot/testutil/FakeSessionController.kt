package top.guozk.pipilot.testutil

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import top.guozk.pipilot.corenet.ConnectionState
import top.guozk.pipilot.corerpc.AvailableModel
import top.guozk.pipilot.corerpc.BashResult
import top.guozk.pipilot.corerpc.ImagePayload
import top.guozk.pipilot.corerpc.RpcIncomingMessage
import top.guozk.pipilot.corerpc.RpcResponse
import top.guozk.pipilot.corerpc.SessionStats
import top.guozk.pipilot.coresessions.SessionKey
import top.guozk.pipilot.coresessions.SessionRecord
import top.guozk.pipilot.hosts.HostProfile
import top.guozk.pipilot.sessions.ActiveSessionState
import top.guozk.pipilot.sessions.ForkableMessage
import top.guozk.pipilot.sessions.ModelInfo
import top.guozk.pipilot.sessions.SessionBootstrapSnapshot
import top.guozk.pipilot.sessions.SessionController
import top.guozk.pipilot.sessions.SessionFreshnessSnapshot
import top.guozk.pipilot.sessions.SessionSyncMetrics
import top.guozk.pipilot.sessions.SessionTreeSnapshot
import top.guozk.pipilot.sessions.SlashCommandInfo
import top.guozk.pipilot.sessions.TransportPreference
import top.guozk.pipilot.sessions.TreeNavigationResult

@Suppress("TooManyFunctions")
class FakeSessionController : SessionController {
    private val events = MutableSharedFlow<RpcIncomingMessage>(extraBufferCapacity = 16)
    private val streamingState = MutableStateFlow(false)
    private val retryingState = MutableStateFlow(false)
    private val connectionStateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _sessionChanged = MutableSharedFlow<String?>(extraBufferCapacity = 16)
    private val _activeSession = MutableStateFlow<ActiveSessionState?>(null)
    private val _timelineInvalidated = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private val _syncMetrics = MutableStateFlow(SessionSyncMetrics())

    var availableCommands: List<SlashCommandInfo> = emptyList()
    var getCommandsCallCount: Int = 0
    var getLastAssistantTextCallCount: Int = 0
    var importSessionJsonlCallCount: Int = 0
    var sendPromptCallCount: Int = 0
    var steerCallCount: Int = 0
    var followUpCallCount: Int = 0
    var ensureConnectedCallCount: Int = 0
    var resumeCallCount: Int = 0
    var bootstrapCallCount: Int = 0
    var getMessagesCallCount: Int = 0
    var getStateCallCount: Int = 0
    var reloadActiveSessionCallCount: Int = 0
    var getSessionFreshnessCallCount: Int = 0
    var getStateResult: Result<RpcResponse> =
        Result.success(
            RpcResponse(
                type = "response",
                command = "get_state",
                success = true,
            ),
        )
    var reloadActiveSessionResult: Result<String?> = Result.success(null)
    var lastPromptMessage: String? = null
    var beforeSendPrompt: (() -> Unit)? = null
    var lastFreshnessSessionPath: String? = null
    var lastImportedSessionFileName: String? = null
    var lastImportedSessionJsonlContent: String? = null
    var sendPromptResult: Result<Unit> = Result.success(Unit)
    var steerResult: Result<Unit> = Result.success(Unit)
    var followUpResult: Result<Unit> = Result.success(Unit)
    var sendPromptDelayMs: Long = 0L
    var ensureConnectedResult: Result<Unit> = Result.success(Unit)
    var ensureConnectedDelayMs: Long = 0L
    var resumeDelayMs: Long = 0L
    var resumeResult: Result<String?> = Result.success(null)
    var publishActiveKeyOnResume: Boolean = true
    var lastEnsuredHostId: String? = null
    var lastEnsuredCwd: String? = null
    var bootstrapMessagesDelayMs: Long = 0L
    var abortResult: Result<Unit> = Result.success(Unit)
    var abortRetryResult: Result<Unit> = Result.success(Unit)
    var abortCallCount: Int = 0
    var abortRetryCallCount: Int = 0
    var messagesPayload: JsonObject? = null
    var sessionFreshnessResult: Result<SessionFreshnessSnapshot> =
        Result.failure(IllegalStateException("Not used"))
    var cachedSessionTree: SessionTreeSnapshot? = null
    var sessionTreeResult: Result<SessionTreeSnapshot> = Result.failure(IllegalStateException("Not used"))
    var getSessionTreeCallCount: Int = 0
    var sessionTreeDelayMs: Long = 0L
    var treeNavigationResult: Result<TreeNavigationResult> =
        Result.success(
            TreeNavigationResult(
                cancelled = false,
                editorText = null,
                currentLeafId = null,
                sessionPath = null,
            ),
        )
    var getLastAssistantTextResult: Result<String?> = Result.success(null)
    var importSessionJsonlResult: Result<String?> = Result.success(null)
    var lastNavigatedEntryId: String? = null
    var steeringModeResult: Result<Unit> = Result.success(Unit)
    var followUpModeResult: Result<Unit> = Result.success(Unit)
    var newSessionResult: Result<Unit> = Result.success(Unit)
    var renameSessionResult: Result<String?> = Result.success(null)
    var compactSessionResult: Result<String?> = Result.success(null)
    var exportSessionResult: Result<String> = Result.success("/tmp/export.html")
    var renameSessionCallCount: Int = 0
    var compactSessionCallCount: Int = 0
    var exportSessionCallCount: Int = 0
    var newSessionCallCount: Int = 0
    var lastRenamedSessionName: String? = null
    var lastSteeringMode: String? = null
    var lastFollowUpMode: String? = null
    var lastTransportPreference: TransportPreference = TransportPreference.AUTO

    var clientId: String = "fake-client-id"

    override val rpcEvents: SharedFlow<RpcIncomingMessage> = events
    override val connectionState: StateFlow<ConnectionState> = connectionStateFlow
    override val isStreaming: StateFlow<Boolean> = streamingState
    override val isRetrying: StateFlow<Boolean> = retryingState
    override val sessionChanged: SharedFlow<String?> = _sessionChanged
    override val activeSession: StateFlow<ActiveSessionState?> = _activeSession
    override val timelineInvalidated: SharedFlow<Unit> = _timelineInvalidated
    override val syncMetrics: StateFlow<SessionSyncMetrics> = _syncMetrics

    suspend fun emitEvent(event: RpcIncomingMessage) {
        events.emit(event)
    }

    suspend fun emitSessionChanged(sessionPath: String? = null) {
        _activeSession.value = ActiveSessionState(sessionPath, (_activeSession.value?.generation ?: 0L) + 1L)
        _sessionChanged.emit(sessionPath)
    }

    fun setActiveSession(
        key: SessionKey?,
        sessionPath: String? = null,
    ) {
        _activeSession.value =
            ActiveSessionState(
                sessionPath = sessionPath,
                generation = (_activeSession.value?.generation ?: 0L) + 1L,
                sessionKey = key,
            )
    }

    fun beginSessionSwitch(sessionPath: String?) {
        _activeSession.value = ActiveSessionState(sessionPath, (_activeSession.value?.generation ?: 0L) + 1L, true)
    }

    fun finishSessionSwitch(sessionPath: String?) {
        _activeSession.value = _activeSession.value?.copy(sessionPath = sessionPath, isSwitching = false)
    }

    fun setStreaming(isStreaming: Boolean) {
        streamingState.value = isStreaming
    }

    fun setRetrying(isRetrying: Boolean) {
        retryingState.value = isRetrying
    }

    fun setConnectionState(state: ConnectionState) {
        connectionStateFlow.value = state
    }

    fun invalidateTimeline() {
        _timelineInvalidated.tryEmit(Unit)
    }

    override fun setTransportPreference(preference: TransportPreference) {
        lastTransportPreference = preference
    }

    override fun recordSafetyPoll() {
        _syncMetrics.value = _syncMetrics.value.copy(safetyPolls = _syncMetrics.value.safetyPolls + 1)
    }

    override fun getTransportPreference(): TransportPreference = lastTransportPreference

    override fun getEffectiveTransportPreference(): TransportPreference = TransportPreference.WEBSOCKET

    override fun getActiveCwd(): String? = null

    override suspend fun ensureConnected(
        hostProfile: HostProfile,
        token: String,
        cwd: String,
    ): Result<Unit> {
        ensureConnectedCallCount += 1
        lastEnsuredHostId = hostProfile.id
        lastEnsuredCwd = cwd
        if (ensureConnectedDelayMs > 0) delay(ensureConnectedDelayMs)
        return ensureConnectedResult
    }

    override suspend fun disconnect(): Result<Unit> = Result.success(Unit)

    override suspend fun resume(
        hostProfile: HostProfile,
        token: String,
        session: SessionRecord,
    ): Result<String?> {
        resumeCallCount += 1
        if (resumeDelayMs > 0) delay(resumeDelayMs)
        if (resumeResult.isSuccess && publishActiveKeyOnResume && session.hasStableIdentity) {
            setActiveSession(SessionKey(hostProfile.id, requireNotNull(session.sessionId)), session.sessionPath)
        }
        return resumeResult
    }

    override suspend fun bootstrap(onStateAvailable: (RpcResponse) -> Unit): Result<SessionBootstrapSnapshot> {
        bootstrapCallCount += 1
        val stateResponse = getStateResult.getOrElse { return Result.failure(it) }
        onStateAvailable(stateResponse)
        if (bootstrapMessagesDelayMs > 0) delay(bootstrapMessagesDelayMs)
        val messagesResponse =
            RpcResponse(
                type = "response",
                command = "get_messages",
                success = true,
                data = messagesPayload,
            )
        return Result.success(SessionBootstrapSnapshot(stateResponse, messagesResponse))
    }

    override suspend fun getMessages(): Result<RpcResponse> {
        getMessagesCallCount += 1
        return Result.success(
            RpcResponse(
                type = "response",
                command = "get_messages",
                success = true,
                data = messagesPayload,
            ),
        )
    }

    override suspend fun getState(): Result<RpcResponse> {
        getStateCallCount += 1
        return getStateResult
    }

    override suspend fun reloadActiveSessionFromDisk(): Result<String?> {
        reloadActiveSessionCallCount += 1
        return reloadActiveSessionResult
    }

    override suspend fun sendPrompt(
        message: String,
        images: List<ImagePayload>,
        expectedSessionKey: SessionKey?,
    ): Result<Unit> {
        beforeSendPrompt?.invoke()
        if (!matchesExpectedActiveSession(expectedSessionKey)) return activeSessionChangedFailure()
        sendPromptCallCount += 1
        lastPromptMessage = message
        if (sendPromptDelayMs > 0) {
            delay(sendPromptDelayMs)
        }
        return sendPromptResult
    }

    override suspend fun abort(): Result<Unit> {
        abortCallCount += 1
        return abortResult
    }

    override suspend fun steer(
        message: String,
        expectedSessionKey: SessionKey?,
    ): Result<Unit> {
        if (!matchesExpectedActiveSession(expectedSessionKey)) return activeSessionChangedFailure()
        steerCallCount += 1
        return steerResult
    }

    override suspend fun followUp(
        message: String,
        expectedSessionKey: SessionKey?,
    ): Result<Unit> {
        if (!matchesExpectedActiveSession(expectedSessionKey)) return activeSessionChangedFailure()
        followUpCallCount += 1
        return followUpResult
    }

    override suspend fun renameSession(name: String): Result<String?> {
        renameSessionCallCount += 1
        lastRenamedSessionName = name
        return renameSessionResult
    }

    override suspend fun compactSession(): Result<String?> {
        compactSessionCallCount += 1
        return compactSessionResult
    }

    override suspend fun exportSession(): Result<String> {
        exportSessionCallCount += 1
        return exportSessionResult
    }

    override suspend fun forkSessionFromEntryId(entryId: String): Result<String?> = Result.success(null)

    override suspend fun getForkMessages(): Result<List<ForkableMessage>> = Result.success(emptyList())

    override fun getCachedSessionTree(
        sessionPath: String,
        filter: String?,
    ): SessionTreeSnapshot? = cachedSessionTree?.takeIf { it.sessionPath == sessionPath }

    override suspend fun getSessionTree(
        sessionPath: String?,
        filter: String?,
    ): Result<SessionTreeSnapshot> {
        getSessionTreeCallCount += 1
        if (sessionTreeDelayMs > 0) delay(sessionTreeDelayMs)
        return sessionTreeResult
    }

    override suspend fun getSessionFreshness(sessionPath: String): Result<SessionFreshnessSnapshot> {
        getSessionFreshnessCallCount += 1
        lastFreshnessSessionPath = sessionPath
        return sessionFreshnessResult
    }

    override suspend fun navigateTreeToEntry(entryId: String): Result<TreeNavigationResult> {
        lastNavigatedEntryId = entryId
        return treeNavigationResult
    }

    override suspend fun cycleModel(): Result<ModelInfo?> = Result.success(null)

    override suspend fun cycleThinkingLevel(): Result<String?> = Result.success(null)

    override suspend fun setThinkingLevel(level: String): Result<String?> = Result.success(level)

    override suspend fun abortRetry(): Result<Unit> {
        abortRetryCallCount += 1
        return abortRetryResult
    }

    override suspend fun sendExtensionUiResponse(
        requestId: String,
        value: String?,
        confirmed: Boolean?,
        cancelled: Boolean?,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun newSession(): Result<Unit> {
        newSessionCallCount += 1
        return newSessionResult
    }

    override suspend fun getClientId(): String = clientId

    override suspend fun getCommands(): Result<List<SlashCommandInfo>> {
        getCommandsCallCount += 1
        return Result.success(availableCommands)
    }

    override suspend fun getLastAssistantText(): Result<String?> {
        getLastAssistantTextCallCount += 1
        return getLastAssistantTextResult
    }

    override suspend fun importSessionJsonl(
        fileName: String,
        jsonlContent: String,
    ): Result<String?> {
        importSessionJsonlCallCount += 1
        lastImportedSessionFileName = fileName
        lastImportedSessionJsonlContent = jsonlContent
        return importSessionJsonlResult
    }

    override suspend fun executeBash(
        command: String,
        timeoutMs: Int?,
    ): Result<BashResult> =
        Result.success(
            BashResult(
                output = "",
                exitCode = 0,
                wasTruncated = false,
            ),
        )

    override suspend fun abortBash(): Result<Unit> = Result.success(Unit)

    override suspend fun getSessionStats(): Result<SessionStats> =
        Result.success(
            SessionStats(
                inputTokens = 0,
                outputTokens = 0,
                cacheReadTokens = 0,
                cacheWriteTokens = 0,
                totalCost = 0.0,
                messageCount = 0,
                userMessageCount = 0,
                assistantMessageCount = 0,
                toolResultCount = 0,
                sessionPath = null,
            ),
        )

    override suspend fun getAvailableModels(): Result<List<AvailableModel>> = Result.success(emptyList())

    override suspend fun setModel(
        provider: String,
        modelId: String,
    ): Result<ModelInfo?> = Result.success(null)

    override suspend fun setAutoCompaction(enabled: Boolean): Result<Unit> = Result.success(Unit)

    override suspend fun setAutoRetry(enabled: Boolean): Result<Unit> = Result.success(Unit)

    override suspend fun setSteeringMode(mode: String): Result<Unit> {
        lastSteeringMode = mode
        return steeringModeResult
    }

    override suspend fun setFollowUpMode(mode: String): Result<Unit> {
        lastFollowUpMode = mode
        return followUpModeResult
    }

    private fun matchesExpectedActiveSession(expectedSessionKey: SessionKey?): Boolean {
        if (expectedSessionKey == null) return true
        val active = _activeSession.value
        return active?.sessionKey == expectedSessionKey && !active.isSwitching
    }

    private fun activeSessionChangedFailure(): Result<Unit> =
        Result.failure(IllegalStateException("The active session changed before dispatch"))
}
