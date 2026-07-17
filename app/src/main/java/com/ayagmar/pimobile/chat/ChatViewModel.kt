package com.ayagmar.pimobile.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corerpc.AgentEndEvent
import com.ayagmar.pimobile.corerpc.AgentSettledEvent
import com.ayagmar.pimobile.corerpc.AssistantTextAssembler
import com.ayagmar.pimobile.corerpc.AssistantTextUpdate
import com.ayagmar.pimobile.corerpc.AutoCompactionEndEvent
import com.ayagmar.pimobile.corerpc.AutoCompactionStartEvent
import com.ayagmar.pimobile.corerpc.AutoRetryEndEvent
import com.ayagmar.pimobile.corerpc.AutoRetryStartEvent
import com.ayagmar.pimobile.corerpc.AvailableModel
import com.ayagmar.pimobile.corerpc.ExtensionErrorEvent
import com.ayagmar.pimobile.corerpc.ExtensionUiRequestEvent
import com.ayagmar.pimobile.corerpc.ImagePayload
import com.ayagmar.pimobile.corerpc.MessageEndEvent
import com.ayagmar.pimobile.corerpc.MessageStartEvent
import com.ayagmar.pimobile.corerpc.MessageUpdateEvent
import com.ayagmar.pimobile.corerpc.RpcResponse
import com.ayagmar.pimobile.corerpc.ToolExecutionEndEvent
import com.ayagmar.pimobile.corerpc.ToolExecutionStartEvent
import com.ayagmar.pimobile.corerpc.ToolExecutionUpdateEvent
import com.ayagmar.pimobile.corerpc.TurnEndEvent
import com.ayagmar.pimobile.corerpc.TurnStartEvent
import com.ayagmar.pimobile.corerpc.UiUpdateThrottler
import com.ayagmar.pimobile.perf.PerformanceMetrics
import com.ayagmar.pimobile.sessions.SessionController
import com.ayagmar.pimobile.sessions.SessionFreshnessFingerprint
import com.ayagmar.pimobile.sessions.SlashCommandInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID

internal const val HISTORY_WINDOW_MAX_ITEMS = 1_200

@Suppress("TooManyFunctions", "LargeClass")
class ChatViewModel(
    private val sessionController: SessionController,
    private val imageEncoder: ImageEncoder? = null,
) : ViewModel() {
    private val assembler = AssistantTextAssembler()
    private val _uiState = MutableStateFlow(ChatUiState(isLoading = true))
    private val assistantUpdateThrottler = UiUpdateThrottler<AssistantTextUpdate>(ASSISTANT_UPDATE_THROTTLE_MS)
    private val toolUpdateThrottlers = mutableMapOf<String, UiUpdateThrottler<ToolExecutionUpdateEvent>>()
    private val toolUpdateFlushJobs = mutableMapOf<String, Job>()
    private var assistantUpdateFlushJob: Job? = null
    private var fullTimeline: List<ChatTimelineItem> = emptyList()
    private var visibleTimelineSize: Int = 0
    private var historyWindowMessages: List<JsonObject> = emptyList()
    private var historyWindowAbsoluteOffset: Int = 0
    private var historyParsedStartIndex: Int = 0
    private val pendingLocalUserIds = ArrayDeque<String>()
    private var thinkingDiagnostics = ThinkingDiagnosticsCounters()
    private var thinkingDiagnosticsRunActive = false
    private var streamingDiagnostics = StreamingDeltaDiagnosticsCounters()
    private var streamingDiagnosticsRunActive = false
    private var streamingDiagnosticsRunStartedAtMs: Long = 0
    private var sessionFreshnessMonitorJob: Job? = null
    private var isChatActive = false
    private var latestSessionPath: String? = null
    private var lastKnownSessionFreshness: SessionFreshnessFingerprint? = null
    private var localSessionMutationGraceUntilMs: Long = 0
    private var isSessionFreshnessUnsupported = false
    private var lastFreshnessWarningAtMs: Long = 0
    private var hasDeferredFreshnessRefresh = false

    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        observeConnection()
        observeStreamingState()
        observeEvents()
        observeActiveSessionIdentity()
        loadInitialMessages(reason = TimelineReloadReason.INITIAL)
        loadSessionStats()
    }

    fun onInputTextChanged(text: String) {
        val slashQuery = extractSlashCommandQuery(text)
        var shouldLoadCommands = false

        _uiState.update { state ->
            if (slashQuery != null) {
                shouldLoadCommands = state.commands.isEmpty() && !state.isLoadingCommands
                state.copy(
                    inputText = text,
                    isCommandPaletteVisible = true,
                    commandsQuery = slashQuery,
                    isCommandPaletteAutoOpened = true,
                )
            } else {
                state.copy(
                    inputText = text,
                    isCommandPaletteVisible =
                        if (state.isCommandPaletteAutoOpened) {
                            false
                        } else {
                            state.isCommandPaletteVisible
                        },
                    commandsQuery = if (state.isCommandPaletteAutoOpened) "" else state.commandsQuery,
                    isCommandPaletteAutoOpened = false,
                )
            }
        }

        if (shouldLoadCommands) {
            loadCommands()
        }
        applyDeferredFreshnessRefreshIfIdle()
    }

    @Suppress("ReturnCount")
    fun sendPrompt() {
        val currentState = _uiState.value
        val message = currentState.inputText.trim()
        val pendingImages = currentState.pendingImages
        if (message.isEmpty() && pendingImages.isEmpty()) return

        if (handleSlashInvocationIfNeeded(message = message, pendingImages = pendingImages)) {
            return
        }

        preparePromptDispatch()
        _uiState.update { it.copy(isDispatchingMessage = true) }
        val optimisticUserId = addOptimisticUserMessage(message = message, pendingImages = pendingImages)

        viewModelScope.launch {
            val imagePayloads = encodePendingImages(pendingImages)

            if (message.isEmpty() && imagePayloads.isEmpty()) {
                handleImageEncodingFailure(optimisticUserId)
                return@launch
            }

            val clearedDraftState = clearDraftAfterPromptDispatch(currentState)

            val result = sessionController.sendPrompt(message, imagePayloads)
            _uiState.update { it.copy(isDispatchingMessage = false) }
            if (result.isFailure) {
                handleSendPromptFailure(
                    result = result,
                    optimisticUserId = optimisticUserId,
                    currentState = currentState,
                    clearedDraftState = clearedDraftState,
                )
            }
        }
    }

    private fun handleSlashInvocationIfNeeded(
        message: String,
        pendingImages: List<PendingImage>,
    ): Boolean {
        val slashInvocation = message.extractKnownSlashInvocation() ?: return false

        if (pendingImages.isNotEmpty()) {
            _uiState.update {
                it.copy(errorMessage = "Image attachments are only supported for normal prompts")
            }
        } else {
            handleKnownSlashCommand(slashInvocation)
        }

        return true
    }

    private fun preparePromptDispatch() {
        _uiState.update { it.copy(sessionCoherencyWarning = null) }

        // Record prompt send for TTFT tracking
        recordMetricsSafely { PerformanceMetrics.recordPromptSend() }
        hasRecordedFirstToken = false
        markLocalSessionMutationExpected()
    }

    private fun addOptimisticUserMessage(
        message: String,
        pendingImages: List<PendingImage>,
    ): String {
        val optimisticUserId = "$LOCAL_USER_ITEM_PREFIX${UUID.randomUUID()}"
        upsertTimelineItem(
            ChatTimelineItem.User(
                id = optimisticUserId,
                text = message,
                imageCount = pendingImages.size,
                images = pendingImages.map { ChatImageSource.LocalUri(it.uri) },
            ),
        )
        pendingLocalUserIds.addLast(optimisticUserId)
        return optimisticUserId
    }

    private suspend fun encodePendingImages(pendingImages: List<PendingImage>): List<ImagePayload> {
        val encoder = imageEncoder
        if (encoder == null || pendingImages.isEmpty()) return emptyList()

        return withContext(Dispatchers.Default) {
            pendingImages.mapNotNull(encoder::encodeToPayload)
        }
    }

    private fun handleImageEncodingFailure(optimisticUserId: String) {
        discardPendingLocalUserItem(optimisticUserId)
        _uiState.update {
            it.copy(
                isDispatchingMessage = false,
                errorMessage = "Unable to attach image. Please try again.",
            )
        }
    }

    private fun clearDraftAfterPromptDispatch(currentState: ChatUiState): DraftClearState {
        var inputWasCleared = false
        var imagesWereCleared = false

        _uiState.update { state ->
            val shouldClearInput = state.inputText == currentState.inputText
            val shouldClearImages = state.pendingImages == currentState.pendingImages
            inputWasCleared = shouldClearInput
            imagesWereCleared = shouldClearImages

            state.copy(
                inputText = if (shouldClearInput) "" else state.inputText,
                pendingImages = if (shouldClearImages) emptyList() else state.pendingImages,
                errorMessage = null,
            )
        }

        return DraftClearState(
            inputWasCleared = inputWasCleared,
            imagesWereCleared = imagesWereCleared,
        )
    }

    private fun handleSendPromptFailure(
        result: Result<Unit>,
        optimisticUserId: String,
        currentState: ChatUiState,
        clearedDraftState: DraftClearState,
    ) {
        discardPendingLocalUserItem(optimisticUserId)
        _uiState.update { state ->
            val shouldRestoreDraft =
                (clearedDraftState.inputWasCleared || clearedDraftState.imagesWereCleared) &&
                    state.inputText.isEmpty() &&
                    state.pendingImages.isEmpty()
            state.copy(
                inputText = if (shouldRestoreDraft) currentState.inputText else state.inputText,
                pendingImages = if (shouldRestoreDraft) currentState.pendingImages else state.pendingImages,
                errorMessage = result.exceptionOrNull()?.message,
            )
        }
    }

    fun abort() {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }

            val abortResult = sessionController.abort()
            val shouldAttemptAbortRetry = _uiState.value.isRetrying || abortResult.isFailure
            val abortRetryResult =
                if (shouldAttemptAbortRetry) {
                    sessionController.abortRetry()
                } else {
                    Result.success(Unit)
                }

            if (abortResult.isFailure && abortRetryResult.isFailure) {
                _uiState.update {
                    it.copy(
                        errorMessage =
                            abortResult.exceptionOrNull()?.message
                                ?: abortRetryResult.exceptionOrNull()?.message,
                    )
                }
            }
        }
    }

    fun steer(message: String) {
        val trimmedMessage = message.trim()
        if (trimmedMessage.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    errorMessage = null,
                    isDispatchingMessage = true,
                )
            }
            markLocalSessionMutationExpected()
            val queueItemId = maybeTrackStreamingQueueItem(PendingQueueType.STEER, trimmedMessage)
            val result = sessionController.steer(trimmedMessage)
            _uiState.update { it.copy(isDispatchingMessage = false) }
            if (result.isSuccess) {
                clearActiveRunDraftAfterDispatch(trimmedMessage)
            } else {
                queueItemId?.let(::removePendingQueueItem)
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun followUp(message: String) {
        val trimmedMessage = message.trim()
        if (trimmedMessage.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    errorMessage = null,
                    isDispatchingMessage = true,
                )
            }
            markLocalSessionMutationExpected()
            val queueItemId = maybeTrackStreamingQueueItem(PendingQueueType.FOLLOW_UP, trimmedMessage)
            val result = sessionController.followUp(trimmedMessage)
            _uiState.update { it.copy(isDispatchingMessage = false) }
            if (result.isSuccess) {
                clearActiveRunDraftAfterDispatch(trimmedMessage)
            } else {
                queueItemId?.let(::removePendingQueueItem)
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    private fun clearActiveRunDraftAfterDispatch(submittedMessage: String) {
        _uiState.update { state ->
            if (state.inputText.trim() == submittedMessage) state.copy(inputText = "") else state
        }
        applyDeferredFreshnessRefreshIfIdle()
    }

    fun cycleModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = sessionController.cycleModel()
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            } else {
                result.getOrNull()?.let { modelInfo ->
                    _uiState.update { it.copy(currentModel = modelInfo) }
                }
            }
        }
    }

    fun cycleThinkingLevel() {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = sessionController.cycleThinkingLevel()
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            } else {
                result.getOrNull()?.let { level ->
                    _uiState.update { it.copy(thinkingLevel = level) }
                }
            }
        }
    }

    fun setThinkingLevel(level: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = sessionController.setThinkingLevel(level)
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            } else {
                _uiState.update { it.copy(thinkingLevel = result.getOrNull() ?: level) }
            }
        }
    }

    fun abortRetry() {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = sessionController.abortRetry()
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun showCommandPalette() {
        _uiState.update {
            it.copy(
                isCommandPaletteVisible = true,
                commandsQuery = "",
                isCommandPaletteAutoOpened = false,
            )
        }
        loadCommands()
    }

    fun hideCommandPalette() {
        _uiState.update {
            it.copy(
                isCommandPaletteVisible = false,
                isCommandPaletteAutoOpened = false,
            )
        }
    }

    fun onCommandsQueryChanged(query: String) {
        _uiState.update { it.copy(commandsQuery = query) }
    }

    fun onCommandSelected(command: SlashCommandInfo) {
        when (command.source) {
            COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
            COMMAND_SOURCE_BUILTIN_UNSUPPORTED,
            -> handleKnownSlashCommand(SlashCommandInvocation(name = command.name, args = null))

            else -> {
                val currentText = _uiState.value.inputText
                val newText = replaceTrailingSlashToken(currentText, command.name)
                _uiState.update {
                    it.copy(
                        inputText = newText,
                        isCommandPaletteVisible = false,
                        isCommandPaletteAutoOpened = false,
                    )
                }
            }
        }
    }

    private fun extractSlashCommandQuery(input: String): String? {
        val trimmed = input.trim()
        return trimmed
            .takeIf { token -> token.isNotEmpty() && token.none(Char::isWhitespace) }
            ?.let { token ->
                SLASH_COMMAND_TOKEN_REGEX.matchEntire(token)?.groupValues?.get(1)
            }
    }

    private fun replaceTrailingSlashToken(
        input: String,
        commandName: String,
    ): String {
        val trimmedInput = input.trimEnd()
        val trailingTokenStart = trimmedInput.lastIndexOfAny(charArrayOf(' ', '\n', '\t')).let { it + 1 }
        val trailingToken = trimmedInput.substring(trailingTokenStart)
        val canReplaceToken = SLASH_COMMAND_TOKEN_REGEX.matches(trailingToken)

        return if (canReplaceToken) {
            trimmedInput.substring(0, trailingTokenStart) + "/$commandName "
        } else if (trimmedInput.isEmpty()) {
            "/$commandName "
        } else {
            "$trimmedInput /$commandName "
        }
    }

    @Suppress("ReturnCount")
    private fun String.extractKnownSlashInvocation(): SlashCommandInvocation? {
        val trimmed = trim()
        if (!trimmed.startsWith('/')) return null

        val token = trimmed.removePrefix("/")
        val name = token.substringBefore(' ').trim().lowercase()
        if (name.isBlank() || name !in KNOWN_SLASH_COMMAND_NAMES) return null

        val args = token.substringAfter(' ', missingDelimiterValue = "").trim().ifBlank { null }
        return SlashCommandInvocation(name = name, args = args)
    }

    private fun handleKnownSlashCommand(invocation: SlashCommandInvocation) {
        _uiState.update {
            it.copy(
                isCommandPaletteVisible = false,
                isCommandPaletteAutoOpened = false,
                commandsQuery = "",
                errorMessage = null,
            )
        }

        when (invocation.name) {
            BUILTIN_TREE_COMMAND -> showTreeSheet()
            BUILTIN_STATS_COMMAND -> runStatsSlashCommand()
            BUILTIN_MODEL_COMMAND -> showModelPicker()
            BUILTIN_SESSION_COMMAND -> showStatsSheet()
            BUILTIN_COMPACT_COMMAND -> compactNow()
            BUILTIN_FORK_COMMAND -> runForkSlashCommand()
            BUILTIN_EXPORT_COMMAND -> runExportSlashCommand()
            BUILTIN_IMPORT_COMMAND -> runImportSlashCommand()
            BUILTIN_NEW_COMMAND -> runNewSessionSlashCommand()
            BUILTIN_NAME_COMMAND -> runRenameSlashCommand(invocation.args)
            BUILTIN_COPY_COMMAND -> runCopySlashCommand()
            else -> showUnsupportedKnownSlashCommandMessage(invocation.name)
        }
    }

    private fun runStatsSlashCommand() {
        invokeInternalWorkflowCommand(INTERNAL_STATS_WORKFLOW_COMMAND) {
            showStatsSheet()
        }
    }

    private fun runForkSlashCommand() {
        showTreeSheet()
        addSystemNotification(
            message = "Select an entry and tap Fork to create a new branch session",
            type = "info",
        )
    }

    private fun showUnsupportedKnownSlashCommandMessage(commandName: String) {
        when (commandName) {
            BUILTIN_SETTINGS_COMMAND -> {
                _uiState.update {
                    it.copy(errorMessage = "Use the Settings tab for /settings on mobile")
                }
            }
            BUILTIN_HOTKEYS_COMMAND -> {
                _uiState.update {
                    it.copy(errorMessage = "/hotkeys is not supported on mobile yet")
                }
            }
            BUILTIN_RESUME_COMMAND,
            BUILTIN_SHARE_COMMAND,
            BUILTIN_RELOAD_COMMAND,
            BUILTIN_CHANGELOG_COMMAND,
            BUILTIN_SCOPED_MODELS_COMMAND,
            -> {
                _uiState.update {
                    it.copy(errorMessage = "/$commandName is not available on mobile yet")
                }
            }
            else -> {
                _uiState.update {
                    it.copy(errorMessage = "/$commandName is interactive-only and unavailable via RPC prompt")
                }
            }
        }
    }

    private fun runRenameSlashCommand(args: String?) {
        val newName = args?.trim().orEmpty()
        if (newName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Usage: /name <session name>") }
            return
        }

        viewModelScope.launch {
            markLocalSessionMutationExpected()
            val result = sessionController.renameSession(newName)
            if (result.isSuccess) {
                _uiState.update { it.copy(sessionName = newName) }
                addSystemNotification(message = "Session renamed to \"$newName\"", type = "info")
            } else {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun exportSession() {
        runExportSlashCommand()
    }

    private fun runExportSlashCommand() {
        viewModelScope.launch {
            val result = sessionController.exportSession()
            if (result.isSuccess) {
                val exportPath = result.getOrNull()
                addSystemNotification(
                    message = "Session exported${if (exportPath.isNullOrBlank()) "" else " to $exportPath"}",
                    type = "info",
                )
            } else {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun copyLastResponse() {
        runCopySlashCommand()
    }

    private fun runCopySlashCommand() {
        viewModelScope.launch {
            val result = sessionController.getLastAssistantText()
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
                return@launch
            }

            val assistantText = result.getOrNull()
            if (assistantText.isNullOrBlank()) {
                addSystemNotification(message = "No assistant response is available to copy", type = "warning")
                return@launch
            }

            _uiState.update { it.copy(pendingClipboardText = assistantText) }
        }
    }

    private fun runImportSlashCommand() {
        _uiState.update {
            it.copy(
                pendingImportRequestToken = UUID.randomUUID().toString(),
                errorMessage = null,
            )
        }
    }

    private fun runNewSessionSlashCommand() {
        viewModelScope.launch {
            markLocalSessionMutationExpected()
            val result = sessionController.newSession()
            if (result.isSuccess) {
                addSystemNotification(message = "Started a new session", type = "info")
            } else {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    private fun invokeInternalWorkflowCommand(
        commandName: String,
        onFailure: (() -> Unit)? = null,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }

            val commandsResult = sessionController.getCommands()
            val isCommandAvailable =
                commandsResult.getOrNull()
                    ?.any { command -> command.name.equals(commandName, ignoreCase = true) } == true

            if (!isCommandAvailable) {
                val message =
                    commandsResult.exceptionOrNull()?.message
                        ?: "Workflow command /$commandName is unavailable in this runtime"
                handleWorkflowCommandFailure(message, onFailure)
                return@launch
            }

            val result = sessionController.sendPrompt(message = "/$commandName")
            if (result.isFailure) {
                handleWorkflowCommandFailure(result.exceptionOrNull()?.message, onFailure)
            }
        }
    }

    private fun handleWorkflowCommandFailure(
        message: String?,
        onFailure: (() -> Unit)? = null,
    ) {
        if (onFailure != null) {
            onFailure()
            return
        }

        _uiState.update { it.copy(errorMessage = message ?: "Failed to run workflow command") }
    }

    private fun loadCommands() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCommands = true) }
            val result = sessionController.getCommands()
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        commands = mergeRpcCommandsWithBuiltins(result.getOrNull().orEmpty()),
                        isLoadingCommands = false,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        commands = mergeRpcCommandsWithBuiltins(emptyList()),
                        isLoadingCommands = false,
                        errorMessage = result.exceptionOrNull()?.message,
                    )
                }
            }
        }
    }

    private fun mergeRpcCommandsWithBuiltins(rpcCommands: List<SlashCommandInfo>): List<SlashCommandInfo> {
        val visibleRpcCommands =
            rpcCommands.filterNot { command -> command.name.lowercase() in INTERNAL_HIDDEN_COMMAND_NAMES }
        if (visibleRpcCommands.isEmpty()) {
            return BUILTIN_COMMANDS
        }

        val knownNames = visibleRpcCommands.map { it.name.lowercase() }.toSet()
        val missingBuiltins = BUILTIN_COMMANDS.filterNot { it.name.lowercase() in knownNames }
        return visibleRpcCommands + missingBuiltins
    }

    fun toggleToolExpansion(itemId: String) {
        updateTimelineState { state ->
            ChatTimelineReducer.toggleToolExpansion(state, itemId)
        }
    }

    fun toggleDiffExpansion(itemId: String) {
        updateTimelineState { state ->
            ChatTimelineReducer.toggleDiffExpansion(state, itemId)
        }
    }

    private fun observeConnection() {
        viewModelScope.launch {
            sessionController.connectionState.collect { state ->
                val previousState = _uiState.value.connectionState
                val timelineEmpty = _uiState.value.timeline.isEmpty()
                _uiState.update { current ->
                    current.copy(connectionState = state)
                }

                if (state == ConnectionState.CONNECTED && previousState != ConnectionState.CONNECTED) {
                    startSessionFreshnessMonitor()
                } else if (state != ConnectionState.CONNECTED && previousState == ConnectionState.CONNECTED) {
                    stopSessionFreshnessMonitor()
                }

                // Reload messages when connection becomes active and timeline is empty
                if (state == ConnectionState.CONNECTED && previousState != ConnectionState.CONNECTED && timelineEmpty) {
                    loadInitialMessages(reason = TimelineReloadReason.CONNECTION_RECOVERY)
                }
            }
        }
    }

    fun onChatActiveChanged(active: Boolean) {
        isChatActive = active
        if (active && sessionController.connectionState.value == ConnectionState.CONNECTED) {
            startSessionFreshnessMonitor()
        } else if (!active) {
            stopSessionFreshnessMonitor()
        }
    }

    private fun startSessionFreshnessMonitor() {
        if (!isChatActive || sessionFreshnessMonitorJob?.isActive == true || isSessionFreshnessUnsupported) {
            return
        }

        sessionFreshnessMonitorJob =
            viewModelScope.launch {
                while (isActive) {
                    refreshSessionFreshness(trigger = FreshnessCheckTrigger.POLL)
                    delay(SESSION_FRESHNESS_POLL_INTERVAL_MS)
                }
            }
    }

    private fun stopSessionFreshnessMonitor() {
        sessionFreshnessMonitorJob?.cancel()
        sessionFreshnessMonitorJob = null
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun refreshSessionFreshness(trigger: FreshnessCheckTrigger) {
        if (trigger == FreshnessCheckTrigger.POLL) {
            sessionController.recordSafetyPoll()
        }
        if (isSessionFreshnessUnsupported) {
            return
        }

        val sessionPath = latestSessionPath
        if (sessionPath == null) {
            return
        }

        val freshnessResult = sessionController.getSessionFreshness(sessionPath)
        val freshness = freshnessResult.getOrNull()

        if (freshness == null) {
            val errorMessage = freshnessResult.exceptionOrNull()?.message.orEmpty()
            if (errorMessage.contains("unsupported_bridge_message", ignoreCase = true)) {
                isSessionFreshnessUnsupported = true
                stopSessionFreshnessMonitor()
            }
        } else {
            latestSessionPath = freshness.sessionPath
            val previous = lastKnownSessionFreshness

            val state = _uiState.value
            val lock = freshness.lock
            val differentClientOwnsLock =
                !lock.cwdOwnerClientId.isNullOrBlank() && !lock.isCurrentClientCwdOwner ||
                    !lock.sessionOwnerClientId.isNullOrBlank() && !lock.isCurrentClientSessionOwner
            val action =
                classifySessionFreshness(
                    SessionFreshnessPolicyInput(
                        fingerprintChanged =
                            trigger != FreshnessCheckTrigger.POST_LOAD &&
                                previous != null && previous != freshness.fingerprint,
                        currentClientOwnsLock =
                            lock.isCurrentClientCwdOwner || lock.isCurrentClientSessionOwner,
                        differentClientOwnsLock = differentClientOwnsLock,
                        chatIsBusy = isChatBusy(state),
                        insideLocalMutationGraceWindow = isWithinLocalMutationGraceWindow(),
                    ),
                )

            when (action) {
                SessionFreshnessAction.UPDATE_BASELINE -> Unit
                SessionFreshnessAction.REFRESH_SILENTLY -> {
                    hasDeferredFreshnessRefresh = false
                    loadInitialMessages(reason = TimelineReloadReason.AUTO_FRESHNESS_REFRESH)
                }
                SessionFreshnessAction.DEFER_REFRESH -> hasDeferredFreshnessRefresh = true
                SessionFreshnessAction.SHOW_CONFLICT -> showSessionFreshnessConflict(trigger)
            }
            lastKnownSessionFreshness = freshness.fingerprint

            val currentClientOwnsLock = lock.isCurrentClientCwdOwner || lock.isCurrentClientSessionOwner
            if (currentClientOwnsLock && !differentClientOwnsLock) {
                _uiState.update { it.copy(sessionCoherencyWarning = null) }
            }
        }
    }

    private fun showSessionFreshnessConflict(trigger: FreshnessCheckTrigger) {
        _uiState.update {
            it.copy(sessionCoherencyWarning = SESSION_COHERENCY_WARNING_MESSAGE)
        }

        if (trigger == FreshnessCheckTrigger.POLL && shouldEmitFreshnessWarning()) {
            addSystemNotification(
                message = "Another client is editing this session. Use Sync now before continuing.",
                type = "warning",
            )
        }
    }

    private fun shouldEmitFreshnessWarning(): Boolean {
        val now = System.currentTimeMillis()

        val elapsedMs = now - lastFreshnessWarningAtMs
        val shouldEmit =
            lastFreshnessWarningAtMs == 0L || elapsedMs >= SESSION_FRESHNESS_WARNING_COOLDOWN_MS

        if (shouldEmit) {
            lastFreshnessWarningAtMs = now
        }

        return shouldEmit
    }

    private fun resetFreshnessWarningThrottle() {
        lastFreshnessWarningAtMs = 0L
    }

    private fun markLocalSessionMutationExpected() {
        localSessionMutationGraceUntilMs = System.currentTimeMillis() + LOCAL_SESSION_MUTATION_GRACE_MS
    }

    private fun isWithinLocalMutationGraceWindow(): Boolean {
        return System.currentTimeMillis() <= localSessionMutationGraceUntilMs
    }

    private fun observeStreamingState() {
        viewModelScope.launch {
            sessionController.isStreaming.collect { isStreaming ->
                val wasStreaming = _uiState.value.isStreaming

                if (!wasStreaming && isStreaming) {
                    resetThinkingDiagnostics(startNewRun = true)
                    resetStreamingDiagnostics(startNewRun = true)
                } else if (wasStreaming && !isStreaming) {
                    logThinkingDiagnostics(reason = "streaming_state_complete")
                    logStreamingDiagnostics(reason = "streaming_state_complete")
                    clearStreamingTimelineFlags()
                }

                _uiState.update { current ->
                    current.copy(
                        isStreaming = isStreaming,
                        pendingQueueItems = if (isStreaming) current.pendingQueueItems else emptyList(),
                        pendingMessageCount = if (isStreaming) current.pendingMessageCount else 0,
                    )
                }

                if (!isStreaming) {
                    applyDeferredFreshnessRefreshIfIdle()
                }
            }
        }
    }

    private fun applyDeferredFreshnessRefreshIfIdle() {
        if (!shouldApplyDeferredFreshnessRefresh(hasDeferredFreshnessRefresh, isChatBusy(_uiState.value))) return
        hasDeferredFreshnessRefresh = false
        loadInitialMessages(reason = TimelineReloadReason.AUTO_FRESHNESS_REFRESH)
    }

    private fun isChatBusy(state: ChatUiState): Boolean =
        state.isStreaming ||
            state.isRetrying ||
            state.isSyncingSession ||
            state.inputText.isNotBlank() ||
            state.pendingImages.isNotEmpty()

    private fun maybeTrackStreamingQueueItem(
        type: PendingQueueType,
        message: String,
    ): String? {
        val state = _uiState.value
        if (!state.isStreaming) return null

        val mode =
            when (type) {
                PendingQueueType.STEER -> state.steeringMode
                PendingQueueType.FOLLOW_UP -> state.followUpMode
            }

        val itemId = UUID.randomUUID().toString()
        val queueItem =
            PendingQueueItem(
                id = itemId,
                type = type,
                message = message,
                mode = mode,
            )

        _uiState.update { current ->
            val nextPendingQueueItems =
                (current.pendingQueueItems + queueItem)
                    .takeLast(MAX_PENDING_QUEUE_ITEMS)

            current.copy(
                pendingQueueItems = nextPendingQueueItems,
                pendingMessageCount = nextPendingQueueItems.size,
            )
        }

        return itemId
    }

    private inline fun recordMetricsSafely(record: () -> Unit) {
        runCatching(record)
    }

    private fun observeActiveSessionIdentity() {
        viewModelScope.launch {
            sessionController.activeSession.collect { identity ->
                if (identity?.isSwitching != true) return@collect

                // A retained Chat destination must never keep painting the source session
                // while the bridge is switching. The generation also guards late replies.
                initialLoadJob?.cancel()
                treeLoadJob?.cancel()
                fullTimeline = emptyList()
                visibleTimelineSize = 0
                pendingLocalUserIds.clear()
                resetHistoryWindow()
                latestSessionPath = identity.sessionPath
                lastKnownSessionFreshness = null
                _uiState.update {
                    it.copy(
                        isLoading = true,
                        timeline = emptyList(),
                        hasOlderMessages = false,
                        hiddenHistoryCount = 0,
                        sessionPath = identity.sessionPath,
                        sessionName = null,
                        errorMessage = null,
                        sessionCoherencyWarning = null,
                        isSyncingSession = true,
                        sessionTree = null,
                        isLoadingTree = false,
                        treeErrorMessage = null,
                        extensionStatuses = emptyMap(),
                    )
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun observeEvents() {
        // Observe session changes and reload timeline
        viewModelScope.launch {
            sessionController.sessionChanged.collect {
                // Reset state for new session
                logThinkingDiagnostics(reason = "session_changed")
                logStreamingDiagnostics(reason = "session_changed")
                hasRecordedFirstToken = false
                resetStreamingUpdateState()
                resetThinkingDiagnostics(startNewRun = false)
                resetStreamingDiagnostics(startNewRun = false)
                fullTimeline = emptyList()
                visibleTimelineSize = 0
                pendingLocalUserIds.clear()
                resetHistoryWindow()
                latestSessionPath = null
                lastKnownSessionFreshness = null
                localSessionMutationGraceUntilMs = 0
                resetFreshnessWarningThrottle()
                _uiState.update {
                    it.copy(
                        sessionCoherencyWarning = null,
                        isSyncingSession = false,
                        extensionStatuses = emptyMap(),
                    )
                }
                loadInitialMessages(reason = TimelineReloadReason.SESSION_CHANGED)
            }
        }

        viewModelScope.launch {
            sessionController.timelineInvalidated.collect {
                if (initialLoadJob?.isActive != true) {
                    loadInitialMessages(reason = TimelineReloadReason.CONNECTION_RECOVERY)
                }
            }
        }

        viewModelScope.launch {
            sessionController.rpcEvents.collect { event ->
                when (event) {
                    is MessageUpdateEvent -> handleMessageUpdate(event)
                    is MessageStartEvent -> handleMessageStart()
                    is MessageEndEvent -> {
                        flushPendingAssistantUpdate(force = true)
                        handleMessageEnd(event)
                    }
                    is TurnStartEvent -> handleTurnStart()
                    is TurnEndEvent -> {
                        flushAllPendingStreamUpdates(force = true)
                        handleTurnEnd()
                    }
                    is ToolExecutionStartEvent -> {
                        flushPendingToolUpdate(event.toolCallId, force = true)
                        handleToolStart(event)
                    }
                    is ToolExecutionUpdateEvent -> handleToolUpdate(event)
                    is ToolExecutionEndEvent -> {
                        flushPendingToolUpdate(event.toolCallId, force = true)
                        handleToolEnd(event)
                        clearToolUpdateThrottle(event.toolCallId)
                    }
                    is ExtensionUiRequestEvent -> handleExtensionUiRequest(event)
                    is ExtensionErrorEvent -> {
                        flushAllPendingStreamUpdates(force = true)
                        handleExtensionError(event)
                    }
                    is AutoCompactionStartEvent -> handleCompactionStart(event)
                    is AutoCompactionEndEvent -> handleCompactionEnd(event)
                    is AutoRetryStartEvent -> handleRetryStart(event)
                    is AutoRetryEndEvent -> handleRetryEnd(event)
                    is AgentEndEvent -> {
                        flushAllPendingStreamUpdates(force = true)
                        logThinkingDiagnostics(reason = "agent_end")
                        logStreamingDiagnostics(reason = "agent_end")
                    }
                    is AgentSettledEvent -> handleAgentSettled()
                    else -> Unit
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun handleExtensionUiRequest(event: ExtensionUiRequestEvent) {
        when (event.method) {
            "select" -> showSelectDialog(event)
            "confirm" -> showConfirmDialog(event)
            "input" -> showInputDialog(event)
            "editor" -> showEditorDialog(event)
            "notify" -> addNotification(event)
            "setStatus" -> updateExtensionStatus(event)
            "setWidget" -> updateExtensionWidget(event)
            "setTitle" -> updateExtensionTitle(event)
            "set_editor_text" -> updateEditorText(event)
            else -> Unit
        }
    }

    private fun showSelectDialog(event: ExtensionUiRequestEvent) {
        _uiState.update {
            it.copy(
                activeExtensionRequest =
                    ExtensionUiRequest.Select(
                        requestId = event.id,
                        title = event.title ?: "Select",
                        options = event.options ?: emptyList(),
                    ),
            )
        }
    }

    private fun showConfirmDialog(event: ExtensionUiRequestEvent) {
        _uiState.update {
            it.copy(
                activeExtensionRequest =
                    ExtensionUiRequest.Confirm(
                        requestId = event.id,
                        title = event.title ?: "Confirm",
                        message = event.message ?: "",
                    ),
            )
        }
    }

    private fun showInputDialog(event: ExtensionUiRequestEvent) {
        _uiState.update {
            it.copy(
                activeExtensionRequest =
                    ExtensionUiRequest.Input(
                        requestId = event.id,
                        title = event.title ?: "Input",
                        placeholder = event.placeholder,
                    ),
            )
        }
    }

    private fun showEditorDialog(event: ExtensionUiRequestEvent) {
        _uiState.update {
            it.copy(
                activeExtensionRequest =
                    ExtensionUiRequest.Editor(
                        requestId = event.id,
                        title = event.title ?: "Editor",
                        prefill = event.prefill ?: "",
                    ),
            )
        }
    }

    private fun addNotification(event: ExtensionUiRequestEvent) {
        appendNotification(
            message = event.message.orEmpty().stripAnsi(),
            type = event.notifyType ?: "info",
        )
    }

    private fun updateExtensionStatus(event: ExtensionUiRequestEvent) {
        val key = event.statusKey ?: "default"
        val text = event.statusText?.stripAnsi()

        if (key == INTERNAL_WORKFLOW_STATUS_KEY) {
            if (text != null) {
                handleInternalWorkflowStatus(text)
            }
            return
        }

        _uiState.update { state ->
            val updatedStatuses = state.extensionStatuses.toMutableMap()
            if (text == null) {
                updatedStatuses.remove(key)
            } else {
                updatedStatuses[key] = text
            }
            state.copy(extensionStatuses = updatedStatuses)
        }
    }

    private fun handleInternalWorkflowStatus(payloadText: String) {
        val action =
            runCatching {
                Json.parseToJsonElement(payloadText).jsonObject.stringField("action")
            }.getOrNull()

        when (action) {
            INTERNAL_WORKFLOW_ACTION_OPEN_STATS -> showStatsSheet()
            else -> Unit
        }
    }

    private fun updateExtensionWidget(event: ExtensionUiRequestEvent) {
        val key = event.widgetKey ?: "default"
        val lines = event.widgetLines?.map { it.stripAnsi() }
        _uiState.update { state ->
            val newWidgets = state.extensionWidgets.toMutableMap()
            if (lines == null) {
                newWidgets.remove(key)
            } else {
                newWidgets[key] =
                    ExtensionWidget(
                        lines = lines,
                        placement = event.widgetPlacement ?: "aboveEditor",
                    )
            }
            state.copy(extensionWidgets = newWidgets)
        }
    }

    private fun updateExtensionTitle(event: ExtensionUiRequestEvent) {
        event.title?.let { title ->
            _uiState.update { it.copy(extensionTitle = title.stripAnsi()) }
        }
    }

    private fun updateEditorText(event: ExtensionUiRequestEvent) {
        event.text?.let { text ->
            _uiState.update { it.copy(inputText = text) }
            applyDeferredFreshnessRefreshIfIdle()
        }
    }

    private fun handleMessageStart() {
        // Silently track message start - no UI notification to reduce spam
    }

    private fun handleMessageEnd(event: MessageEndEvent) {
        val message = event.message
        val role = message?.stringField("role") ?: "assistant"

        // Add user messages to timeline
        if (role == "user" && message != null) {
            val content = message["content"]
            val text = extractUserText(content)
            val imageCount = extractUserImageCount(content)
            val entryId = message.stringField("entryId") ?: UUID.randomUUID().toString()
            val userItem =
                ChatTimelineItem.User(
                    id = "user-$entryId",
                    text = text,
                    imageCount = imageCount,
                    images = extractUserImages(content),
                )
            replacePendingUserItemOrUpsert(userItem)
        }
    }

    private fun handleTurnStart() {
        // Silently track turn start - no UI notification to reduce spam
    }

    private fun handleTurnEnd() {
        // Refresh stats after each low-level turn while keeping the run active until agent_settled.
        loadSessionStats()
    }

    private fun handleAgentSettled() {
        clearStreamingTimelineFlags()
        _uiState.update {
            it.copy(
                isStreaming = false,
                pendingQueueItems = emptyList(),
            )
        }
        applyDeferredFreshnessRefreshIfIdle()
    }

    private fun handleExtensionError(event: ExtensionErrorEvent) {
        val extension = firstNonBlank(event.extensionPath, event.path, "unknown-extension")
        val sourceEvent = firstNonBlank(event.event, event.extensionEvent, "unknown-event")
        val error = firstNonBlank(event.error, event.message, "Unknown extension error")
        addSystemNotification("Extension error [$extension:$sourceEvent] $error", "error")
    }

    private fun handleCompactionStart(event: AutoCompactionStartEvent) {
        val message =
            when (event.reason) {
                "threshold" -> "Compacting context (approaching limit)..."
                "overflow" -> "Compacting context (overflow recovery)..."
                else -> "Compacting context..."
            }
        addSystemNotification(message, "info")
    }

    private fun handleCompactionEnd(event: AutoCompactionEndEvent) {
        val message =
            when {
                event.aborted -> "Compaction aborted"
                event.willRetry -> "Compaction complete, retrying..."
                else -> "Context compacted successfully"
            }
        val type = if (event.aborted) "warning" else "info"
        addSystemNotification(message, type)
    }

    @Suppress("MagicNumber")
    private fun handleRetryStart(event: AutoRetryStartEvent) {
        _uiState.update { it.copy(isRetrying = true) }
        val message = "Retrying (${event.attempt}/${event.maxAttempts}) in ${event.delayMs / 1000}s..."
        addSystemNotification(message, "warning")
    }

    private fun handleRetryEnd(event: AutoRetryEndEvent) {
        _uiState.update { it.copy(isRetrying = false) }
        applyDeferredFreshnessRefreshIfIdle()
        val message =
            if (event.success) {
                "Retry successful (attempt ${event.attempt})"
            } else {
                "Max retries exceeded: ${event.finalError ?: "Unknown error"}"
            }
        val type = if (event.success) "info" else "error"
        addSystemNotification(message, type)
    }

    private fun trackThinkingEventDiagnostics(event: MessageUpdateEvent) {
        val assistantEvent = event.assistantMessageEvent ?: return
        when (assistantEvent.type) {
            "thinking_start" -> {
                if (!thinkingDiagnosticsRunActive) {
                    resetThinkingDiagnostics(startNewRun = true)
                }
                thinkingDiagnostics = thinkingDiagnostics.copy(startEvents = thinkingDiagnostics.startEvents + 1)
            }

            "thinking_delta" -> {
                if (!thinkingDiagnosticsRunActive) {
                    resetThinkingDiagnostics(startNewRun = true)
                }
                val deltaLength = assistantEvent.delta?.length ?: 0
                thinkingDiagnostics =
                    thinkingDiagnostics.copy(
                        deltaEvents = thinkingDiagnostics.deltaEvents + 1,
                        deltaChars = thinkingDiagnostics.deltaChars + deltaLength,
                    )
            }

            "thinking_end" -> {
                if (!thinkingDiagnosticsRunActive) {
                    resetThinkingDiagnostics(startNewRun = true)
                }
                val payload = assistantEvent.thinking ?: assistantEvent.content
                thinkingDiagnostics =
                    thinkingDiagnostics.copy(
                        endEvents = thinkingDiagnostics.endEvents + 1,
                        endPayloadEvents =
                            thinkingDiagnostics.endPayloadEvents +
                                if (payload != null) {
                                    1
                                } else {
                                    0
                                },
                        endPayloadChars = thinkingDiagnostics.endPayloadChars + (payload?.length ?: 0),
                    )
            }
        }
    }

    private fun trackThinkingRenderDiagnostics(update: AssistantTextUpdate) {
        if (update.thinking.isNullOrBlank()) return
        if (!thinkingDiagnosticsRunActive) return

        thinkingDiagnostics =
            thinkingDiagnostics.copy(
                renderedThinkingUpdates = thinkingDiagnostics.renderedThinkingUpdates + 1,
                renderedThinkingCompleteEvents =
                    thinkingDiagnostics.renderedThinkingCompleteEvents +
                        if (update.isThinkingComplete) {
                            1
                        } else {
                            0
                        },
            )
    }

    private fun resetThinkingDiagnostics(startNewRun: Boolean) {
        thinkingDiagnostics = ThinkingDiagnosticsCounters()
        thinkingDiagnosticsRunActive = startNewRun
    }

    private fun logThinkingDiagnostics(reason: String) {
        if (!thinkingDiagnosticsRunActive) return

        val snapshot = thinkingDiagnostics
        val totalThinkingEvents = snapshot.startEvents + snapshot.deltaEvents + snapshot.endEvents
        val assessment =
            when {
                totalThinkingEvents == 0 -> "provider_no_thinking_events"
                snapshot.renderedThinkingUpdates == 0 -> "client_rendering_gap_possible"
                else -> "thinking_rendered"
            }

        val message =
            "reason=$reason start=${snapshot.startEvents} " +
                "delta=${snapshot.deltaEvents} deltaChars=${snapshot.deltaChars} " +
                "end=${snapshot.endEvents} endPayload=${snapshot.endPayloadEvents} " +
                "endPayloadChars=${snapshot.endPayloadChars} " +
                "rendered=${snapshot.renderedThinkingUpdates} " +
                "renderedComplete=${snapshot.renderedThinkingCompleteEvents} " +
                "assessment=$assessment"

        emitThinkingDiagnosticsLog(message)
        resetThinkingDiagnostics(startNewRun = false)
    }

    private fun emitThinkingDiagnosticsLog(message: String) {
        val prefixed = "thinking_diagnostics $message"
        runCatching {
            android.util.Log.i(THINKING_DIAGNOSTICS_LOG_TAG, prefixed)
        }.onFailure {
            println("$THINKING_DIAGNOSTICS_LOG_TAG: $prefixed")
        }
    }

    private fun trackStreamingEventDiagnostics(event: MessageUpdateEvent) {
        val assistantEventType = event.assistantMessageEvent?.type
        if (!streamingDiagnosticsRunActive) {
            if (assistantEventType == null) return
            resetStreamingDiagnostics(startNewRun = true)
        }

        incrementStreamingDiagnostics { counters ->
            counters.copy(messageUpdateEvents = counters.messageUpdateEvents + 1)
        }

        when (assistantEventType) {
            "text_delta" ->
                incrementStreamingDiagnostics { counters ->
                    counters.copy(
                        assistantDeltaEvents = counters.assistantDeltaEvents + 1,
                        textDeltaEvents = counters.textDeltaEvents + 1,
                    )
                }

            "thinking_delta" ->
                incrementStreamingDiagnostics { counters ->
                    counters.copy(
                        assistantDeltaEvents = counters.assistantDeltaEvents + 1,
                        thinkingDeltaEvents = counters.thinkingDeltaEvents + 1,
                    )
                }

            "text_start",
            "text_end",
            "thinking_start",
            "thinking_end",
            ->
                incrementStreamingDiagnostics { counters ->
                    counters.copy(assistantNonDeltaEvents = counters.assistantNonDeltaEvents + 1)
                }
        }
    }

    private fun trackStreamingRenderDiagnostics(source: AssistantUpdateSource) {
        if (!streamingDiagnosticsRunActive) return

        when (source) {
            AssistantUpdateSource.IMMEDIATE_DELTA ->
                incrementStreamingDiagnostics { counters ->
                    counters.copy(emittedImmediateDeltaEvents = counters.emittedImmediateDeltaEvents + 1)
                }

            AssistantUpdateSource.FLUSHED_DELTA ->
                incrementStreamingDiagnostics { counters ->
                    counters.copy(emittedFlushedDeltaEvents = counters.emittedFlushedDeltaEvents + 1)
                }

            AssistantUpdateSource.NON_DELTA -> Unit
        }
    }

    private fun incrementStreamingDiagnostics(
        update: (StreamingDeltaDiagnosticsCounters) -> StreamingDeltaDiagnosticsCounters,
    ) {
        streamingDiagnostics = update(streamingDiagnostics)
    }

    private fun resetStreamingDiagnostics(startNewRun: Boolean) {
        streamingDiagnostics = StreamingDeltaDiagnosticsCounters()
        streamingDiagnosticsRunActive = startNewRun
        streamingDiagnosticsRunStartedAtMs = if (startNewRun) System.currentTimeMillis() else 0L
    }

    private fun logStreamingDiagnostics(reason: String) {
        if (!streamingDiagnosticsRunActive) return

        val snapshot = streamingDiagnostics
        val durationMs =
            if (streamingDiagnosticsRunStartedAtMs > 0L) {
                (System.currentTimeMillis() - streamingDiagnosticsRunStartedAtMs).coerceAtLeast(0L)
            } else {
                0L
            }
        val emittedDeltaEvents = snapshot.emittedImmediateDeltaEvents + snapshot.emittedFlushedDeltaEvents
        val assessment =
            when {
                snapshot.assistantDeltaEvents == 0 -> "provider_sparse_or_chunked"
                snapshot.coalescedDeltaEvents > 0 -> "delta_coalesced"
                else -> "delta_live"
            }

        val message =
            "reason=$reason durationMs=$durationMs " +
                "messageUpdates=${snapshot.messageUpdateEvents} " +
                "assistantDelta=${snapshot.assistantDeltaEvents} " +
                "textDelta=${snapshot.textDeltaEvents} " +
                "thinkingDelta=${snapshot.thinkingDeltaEvents} " +
                "assistantNonDelta=${snapshot.assistantNonDeltaEvents} " +
                "coalescedDelta=${snapshot.coalescedDeltaEvents} " +
                "emittedImmediateDelta=${snapshot.emittedImmediateDeltaEvents} " +
                "emittedFlushedDelta=${snapshot.emittedFlushedDeltaEvents} " +
                "emittedDelta=$emittedDeltaEvents " +
                "assessment=$assessment"

        emitStreamingDiagnosticsLog(message)
        resetStreamingDiagnostics(startNewRun = false)
    }

    private fun emitStreamingDiagnosticsLog(message: String) {
        val prefixed = "streaming_diagnostics $message"
        runCatching {
            android.util.Log.i(STREAMING_DIAGNOSTICS_LOG_TAG, prefixed)
        }.onFailure {
            println("$STREAMING_DIAGNOSTICS_LOG_TAG: $prefixed")
        }
    }

    private fun addSystemNotification(
        message: String,
        type: String,
    ) {
        appendNotification(message = message, type = type)
    }

    private fun appendNotification(
        message: String,
        type: String,
    ) {
        _uiState.update { state ->
            val nextNotifications =
                (state.notifications + ExtensionNotification(message = message, type = type))
                    .takeLast(MAX_NOTIFICATIONS)
            state.copy(notifications = nextNotifications)
        }
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    fun sendExtensionUiResponse(
        requestId: String,
        value: String? = null,
        confirmed: Boolean? = null,
        cancelled: Boolean = false,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(activeExtensionRequest = null) }
            val result =
                sessionController.sendExtensionUiResponse(
                    requestId = requestId,
                    value = value,
                    confirmed = confirmed,
                    cancelled = cancelled,
                )
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun dismissExtensionRequest() {
        _uiState.value.activeExtensionRequest?.let { request ->
            sendExtensionUiResponse(
                requestId = request.requestId,
                cancelled = true,
            )
        }
    }

    fun clearNotification(index: Int) {
        _uiState.update { state ->
            val newNotifications = state.notifications.toMutableList()
            if (index in newNotifications.indices) {
                newNotifications.removeAt(index)
            }
            state.copy(notifications = newNotifications)
        }
    }

    fun consumePendingClipboardText(copySucceeded: Boolean) {
        val pendingText = _uiState.value.pendingClipboardText
        if (pendingText == null) {
            return
        }

        _uiState.update { it.copy(pendingClipboardText = null) }
        addSystemNotification(
            message = if (copySucceeded) "Copied last assistant response" else "Failed to copy last assistant response",
            type = if (copySucceeded) "info" else "error",
        )
    }

    fun consumePendingImportRequest() {
        if (_uiState.value.pendingImportRequestToken == null) {
            return
        }

        _uiState.update { it.copy(pendingImportRequestToken = null) }
    }

    fun importSessionJsonl(
        fileName: String,
        jsonlContent: String,
    ) {
        viewModelScope.launch {
            markLocalSessionMutationExpected()
            val result = sessionController.importSessionJsonl(fileName = fileName, jsonlContent = jsonlContent)
            if (result.isSuccess) {
                addSystemNotification(
                    message = "Session imported${if (fileName.isBlank()) "" else " from $fileName"}",
                    type = "info",
                )
            } else {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun onImportSessionReadFailed(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun removePendingQueueItem(itemId: String) {
        _uiState.update { state ->
            val nextPendingQueueItems =
                state.pendingQueueItems.filterNot { item ->
                    item.id == itemId
                }

            state.copy(
                pendingQueueItems = nextPendingQueueItems,
                pendingMessageCount = nextPendingQueueItems.size,
            )
        }
    }

    fun clearPendingQueueItems() {
        _uiState.update {
            it.copy(
                pendingQueueItems = emptyList(),
                pendingMessageCount = 0,
            )
        }
    }

    fun syncNow() {
        if (_uiState.value.isSyncingSession) return

        _uiState.update {
            it.copy(
                isSyncingSession = true,
                errorMessage = null,
            )
        }
        loadInitialMessages(reason = TimelineReloadReason.MANUAL_SYNC)
    }

    fun compactNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = sessionController.compactSession()
            if (result.isSuccess) {
                addSystemNotification("Compaction requested", "info")
                loadSessionStats()
            } else {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun loadOlderMessages() {
        when {
            visibleTimelineSize < fullTimeline.size -> {
                visibleTimelineSize = minOf(visibleTimelineSize + TIMELINE_PAGE_SIZE, fullTimeline.size)
                publishVisibleTimeline()
            }

            historyParsedStartIndex > 0 && historyWindowMessages.isNotEmpty() -> {
                loadOlderHistoryChunk()
            }
        }
    }

    private fun loadOlderHistoryChunk() {
        val nextStartIndex = (historyParsedStartIndex - TIMELINE_PAGE_SIZE).coerceAtLeast(0)
        val olderHistoryItems =
            parseHistoryItems(
                messages = historyWindowMessages,
                absoluteIndexOffset = historyWindowAbsoluteOffset,
                startIndex = nextStartIndex,
                endExclusive = historyParsedStartIndex,
            )

        historyParsedStartIndex = nextStartIndex

        if (olderHistoryItems.isEmpty()) {
            publishVisibleTimeline()
        } else {
            val existingHistoryItems = fullTimeline.filter { item -> item.id.startsWith(HISTORY_ITEM_PREFIX) }
            val mergedHistory = olderHistoryItems + existingHistoryItems
            val mergedTimeline = mergeHistoryWithRealtimeTimeline(mergedHistory)

            fullTimeline =
                ChatTimelineReducer.limitTimeline(
                    timeline = mergedTimeline,
                    maxTimelineItems = MAX_TIMELINE_ITEMS,
                )
            visibleTimelineSize = minOf(visibleTimelineSize + olderHistoryItems.size, fullTimeline.size)
            publishVisibleTimeline()
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun loadInitialMessages(reason: TimelineReloadReason) {
        val shouldForceRuntimeReload =
            reason == TimelineReloadReason.MANUAL_SYNC ||
                reason == TimelineReloadReason.AUTO_FRESHNESS_REFRESH

        initialLoadJob?.cancel()
        val loadGeneration = sessionController.activeSession.value?.generation
        val bootstrapStartedAt = System.currentTimeMillis()
        initialLoadJob =
            viewModelScope.launch(Dispatchers.IO) {
                val reloadResult =
                    if (shouldForceRuntimeReload) {
                        sessionController.reloadActiveSessionFromDisk()
                    } else {
                        null
                    }
                val reloadError = reloadResult?.exceptionOrNull()

                // State is the cheap header phase. Publish it before the bounded timeline
                // arrives so resume has a useful first render without duplicate requests.
                val stateResult =
                    if (reloadError == null) {
                        sessionController.getState()
                    } else {
                        Result.failure(reloadError)
                    }
                recordMetricsSafely {
                    PerformanceMetrics.recordOperation(
                        operation = "chat_state_response",
                        durationMs = System.currentTimeMillis() - bootstrapStartedAt,
                    )
                }
                val stateData = stateResult.getOrNull()?.data
                val metadata =
                    InitialLoadMetadata(
                        modelInfo = stateData?.let { parseModelInfo(it) },
                        thinkingLevel = stateData?.stringField("thinkingLevel"),
                        isStreaming = stateData?.booleanField("isStreaming") ?: false,
                        steeringMode = stateData.deliveryModeField("steeringMode", "steering_mode"),
                        followUpMode = stateData.deliveryModeField("followUpMode", "follow_up_mode"),
                        sessionPath = stateData?.stringField("sessionFile"),
                        sessionName = stateData?.stringField("sessionName"),
                        pendingMessageCount = stateData?.intField("pendingMessageCount") ?: 0,
                    )

                if (loadGeneration != null && sessionController.activeSession.value?.generation != loadGeneration) {
                    return@launch
                }

                _uiState.update { state ->
                    state.copy(
                        currentModel = metadata.modelInfo,
                        thinkingLevel = metadata.thinkingLevel,
                        isStreaming = metadata.isStreaming,
                        steeringMode = metadata.steeringMode,
                        followUpMode = metadata.followUpMode,
                        sessionPath = metadata.sessionPath ?: reloadResult?.getOrNull(),
                        sessionName = metadata.sessionName,
                        pendingMessageCount = metadata.pendingMessageCount,
                    )
                }

                val messagesResult =
                    if (reloadError == null) {
                        sessionController.getMessages()
                    } else {
                        Result.failure(reloadError)
                    }

                if (messagesResult.isSuccess) {
                    recordMetricsSafely {
                        PerformanceMetrics.recordFirstMessagesRendered()
                        PerformanceMetrics.recordOperation(
                            operation = "chat_first_timeline_payload",
                            durationMs = System.currentTimeMillis() - bootstrapStartedAt,
                        )
                    }
                }

                _uiState.update { state ->
                    if (messagesResult.isFailure) {
                        buildInitialLoadFailureState(
                            state = state,
                            messagesResult = messagesResult,
                            metadata = metadata,
                        )
                    } else {
                        buildInitialLoadSuccessState(
                            state = state,
                            messagesData = messagesResult.getOrNull()?.data,
                            metadata = metadata,
                        )
                    }
                }

                latestSessionPath = metadata.sessionPath ?: reloadResult?.getOrNull() ?: latestSessionPath
                refreshSessionFreshness(trigger = FreshnessCheckTrigger.POST_LOAD)

                if (reason == TimelineReloadReason.MANUAL_SYNC) {
                    _uiState.update { state ->
                        state.copy(
                            isSyncingSession = false,
                            sessionCoherencyWarning =
                                if (messagesResult.isFailure) SESSION_COHERENCY_WARNING_MESSAGE else null,
                        )
                    }

                    if (messagesResult.isSuccess) {
                        resetFreshnessWarningThrottle()
                    }
                    applyDeferredFreshnessRefreshIfIdle()
                } else if (reason == TimelineReloadReason.AUTO_FRESHNESS_REFRESH) {
                    _uiState.update {
                        it.copy(
                            sessionCoherencyWarning =
                                if (messagesResult.isSuccess) {
                                    null
                                } else {
                                    SESSION_COHERENCY_WARNING_MESSAGE
                                },
                        )
                    }

                    if (messagesResult.isSuccess) {
                        resetFreshnessWarningThrottle()
                    }
                }
            }
    }

    private fun buildInitialLoadFailureState(
        state: ChatUiState,
        messagesResult: Result<RpcResponse>,
        metadata: InitialLoadMetadata,
    ): ChatUiState {
        fullTimeline = emptyList()
        visibleTimelineSize = 0
        pendingLocalUserIds.clear()
        resetHistoryWindow()

        return state.copy(
            isLoading = false,
            errorMessage = messagesResult.exceptionOrNull()?.message,
            timeline = emptyList(),
            hasOlderMessages = false,
            hiddenHistoryCount = 0,
            currentModel = metadata.modelInfo,
            thinkingLevel = metadata.thinkingLevel,
            isStreaming = metadata.isStreaming,
            steeringMode = metadata.steeringMode,
            followUpMode = metadata.followUpMode,
            sessionName = metadata.sessionName,
            sessionPath = metadata.sessionPath,
            pendingMessageCount = metadata.pendingMessageCount,
        )
    }

    private fun buildInitialLoadSuccessState(
        state: ChatUiState,
        messagesData: JsonObject?,
        metadata: InitialLoadMetadata,
    ): ChatUiState {
        val historyWindow = extractHistoryMessageWindow(messagesData)
        historyWindowMessages = historyWindow.messages
        historyWindowAbsoluteOffset = historyWindow.absoluteOffset
        historyParsedStartIndex = (historyWindowMessages.size - INITIAL_TIMELINE_SIZE).coerceAtLeast(0)

        val historyTimeline =
            parseHistoryItems(
                messages = historyWindowMessages,
                absoluteIndexOffset = historyWindowAbsoluteOffset,
                startIndex = historyParsedStartIndex,
            )

        val mergedTimeline =
            if (state.isLoading) {
                mergeHistoryWithRealtimeTimeline(historyTimeline)
            } else {
                historyTimeline
            }

        setInitialTimeline(mergedTimeline)

        return state.copy(
            isLoading = false,
            errorMessage = null,
            timeline = visibleTimeline(),
            hasOlderMessages = hasOlderMessages(),
            hiddenHistoryCount = hiddenHistoryCount(),
            currentModel = metadata.modelInfo,
            thinkingLevel = metadata.thinkingLevel,
            isStreaming = metadata.isStreaming,
            steeringMode = metadata.steeringMode,
            followUpMode = metadata.followUpMode,
            sessionName = metadata.sessionName,
            sessionPath = metadata.sessionPath,
            pendingMessageCount = metadata.pendingMessageCount,
        )
    }

    private fun resetHistoryWindow() {
        historyWindowMessages = emptyList()
        historyWindowAbsoluteOffset = 0
        historyParsedStartIndex = 0
    }

    private var hasRecordedFirstToken = false
    private var initialLoadJob: Job? = null
    private var treeLoadJob: Job? = null

    private fun handleMessageUpdate(event: MessageUpdateEvent) {
        // Record first token received for TTFT tracking
        if (!hasRecordedFirstToken) {
            recordMetricsSafely { PerformanceMetrics.recordFirstToken() }
            hasRecordedFirstToken = true
        }

        trackThinkingEventDiagnostics(event)
        trackStreamingEventDiagnostics(event)

        val assistantEventType = event.assistantMessageEvent?.type
        when (assistantEventType) {
            "error" -> {
                flushPendingAssistantUpdate(force = true)
                val assistantEvent = event.assistantMessageEvent
                val reason =
                    assistantEvent?.partial?.stringField("reason")
                        ?: event.message?.stringField("stopReason")
                val message = if (reason.isNullOrBlank()) "Assistant run failed" else "Assistant run failed ($reason)"
                addSystemNotification(message, "error")
            }

            "done" -> flushPendingAssistantUpdate(force = true)

            else -> {
                val update = assembler.apply(event)
                if (update != null) {
                    val isHighFrequencyDelta =
                        assistantEventType == "text_delta" ||
                            assistantEventType == "thinking_delta"

                    if (isHighFrequencyDelta) {
                        handleHighFrequencyAssistantUpdate(update)
                    } else {
                        flushPendingAssistantUpdate(force = true)
                        applyAssistantUpdate(update, source = AssistantUpdateSource.NON_DELTA)
                    }
                }
            }
        }
    }

    private fun handleHighFrequencyAssistantUpdate(update: AssistantTextUpdate) {
        val hadPending = assistantUpdateThrottler.hasPending()
        val immediateUpdate = assistantUpdateThrottler.offer(update)

        if (immediateUpdate != null) {
            applyAssistantUpdate(
                update = immediateUpdate,
                source = AssistantUpdateSource.IMMEDIATE_DELTA,
            )
            return
        }

        if (hadPending) {
            incrementStreamingDiagnostics { counters ->
                counters.copy(
                    coalescedDeltaEvents = counters.coalescedDeltaEvents + 1,
                )
            }
        }
        scheduleAssistantUpdateFlush()
    }

    private fun applyAssistantUpdate(
        update: AssistantTextUpdate,
        source: AssistantUpdateSource,
    ) {
        trackThinkingRenderDiagnostics(update)
        trackStreamingRenderDiagnostics(source)

        val itemId = "assistant-stream-${update.messageKey}-${update.contentIndex}"
        val nextItem =
            ChatTimelineItem.Assistant(
                id = itemId,
                text = update.text,
                thinking = update.thinking,
                isThinkingComplete = update.isThinkingComplete,
                isStreaming = !update.isFinal,
            )

        upsertTimelineItem(nextItem)
    }

    fun toggleThinkingExpansion(itemId: String) {
        updateTimelineState { state ->
            ChatTimelineReducer.toggleThinkingExpansion(state, itemId)
        }
    }

    fun toggleToolArgumentsExpansion(itemId: String) {
        _uiState.update { state ->
            ChatTimelineReducer.toggleToolArgumentsExpansion(state, itemId)
        }
    }

    // Bash dialog functions
    fun showBashDialog() {
        _uiState.update {
            it.copy(
                isBashDialogVisible = true,
                bashCommand = "",
                bashOutput = "",
                bashExitCode = null,
                isBashExecuting = false,
                bashWasTruncated = false,
                bashFullLogPath = null,
            )
        }
    }

    fun hideBashDialog() {
        _uiState.update { it.copy(isBashDialogVisible = false) }
    }

    fun onBashCommandChanged(command: String) {
        _uiState.update { it.copy(bashCommand = command) }
    }

    fun executeBash() {
        val command = _uiState.value.bashCommand.trim()
        if (command.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBashExecuting = true,
                    bashOutput = "Executing...\n",
                    bashExitCode = null,
                    bashWasTruncated = false,
                    bashFullLogPath = null,
                )
            }

            val result = sessionController.executeBash(command)

            _uiState.update { state ->
                if (result.isSuccess) {
                    val bashResult = result.getOrNull()!!
                    // Add to history if not already present
                    val newHistory =
                        if (command in state.bashHistory) {
                            state.bashHistory
                        } else {
                            (listOf(command) + state.bashHistory).take(BASH_HISTORY_SIZE)
                        }
                    state.copy(
                        isBashExecuting = false,
                        bashOutput = bashResult.output,
                        bashExitCode = bashResult.exitCode,
                        bashWasTruncated = bashResult.wasTruncated,
                        bashFullLogPath = bashResult.fullLogPath,
                        bashHistory = newHistory,
                    )
                } else {
                    state.copy(
                        isBashExecuting = false,
                        bashOutput = "Error: ${result.exceptionOrNull()?.message ?: "Unknown error"}",
                        bashExitCode = -1,
                    )
                }
            }
        }
    }

    fun abortBash() {
        viewModelScope.launch {
            val result = sessionController.abortBash()
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        isBashExecuting = false,
                        bashOutput = it.bashOutput + "\n--- Aborted ---",
                    )
                }
            }
        }
    }

    fun selectBashHistoryItem(command: String) {
        _uiState.update { it.copy(bashCommand = command) }
    }

    // Session stats functions
    fun showStatsSheet() {
        _uiState.update { it.copy(isStatsSheetVisible = true) }
        loadSessionStats()
    }

    fun hideStatsSheet() {
        _uiState.update { it.copy(isStatsSheetVisible = false) }
    }

    fun refreshSessionStats() {
        loadSessionStats()
    }

    private fun loadSessionStats() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingStats = true) }
            val result = sessionController.getSessionStats()
            _uiState.update { state ->
                if (result.isSuccess) {
                    state.copy(
                        sessionStats = result.getOrNull(),
                        isLoadingStats = false,
                    )
                } else {
                    state.copy(
                        isLoadingStats = false,
                        errorMessage = result.exceptionOrNull()?.message,
                    )
                }
            }
        }
    }

    // Model picker functions
    fun showModelPicker() {
        _uiState.update { it.copy(isModelPickerVisible = true, modelsQuery = "") }
        loadAvailableModels()
    }

    fun hideModelPicker() {
        _uiState.update { it.copy(isModelPickerVisible = false) }
    }

    fun onModelsQueryChanged(query: String) {
        _uiState.update { it.copy(modelsQuery = query) }
    }

    fun selectModel(model: AvailableModel) {
        viewModelScope.launch {
            _uiState.update { it.copy(isModelPickerVisible = false) }
            val result = sessionController.setModel(model.provider, model.id)
            if (result.isSuccess) {
                result.getOrNull()?.let { modelInfo ->
                    _uiState.update { it.copy(currentModel = modelInfo) }
                }
            } else {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    private fun loadAvailableModels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingModels = true) }
            val result = sessionController.getAvailableModels()
            _uiState.update { state ->
                if (result.isSuccess) {
                    state.copy(
                        availableModels = result.getOrNull() ?: emptyList(),
                        isLoadingModels = false,
                    )
                } else {
                    state.copy(
                        isLoadingModels = false,
                        errorMessage = result.exceptionOrNull()?.message,
                    )
                }
            }
        }
    }

    fun showTreeSheet() {
        _uiState.update { it.copy(isTreeSheetVisible = true) }
        loadSessionTree()
    }

    fun hideTreeSheet() {
        treeLoadJob?.cancel()
        _uiState.update { it.copy(isTreeSheetVisible = false) }
    }

    fun setTreeFilter(filter: String) {
        _uiState.update { it.copy(treeFilter = filter) }
        if (_uiState.value.isTreeSheetVisible) {
            loadSessionTree()
        }
    }

    fun forkFromTreeEntry(entryId: String) {
        viewModelScope.launch {
            val result = sessionController.forkSessionFromEntryId(entryId)
            if (result.isSuccess) {
                hideTreeSheet()
            } else {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun jumpAndContinueFromTreeEntry(entryId: String) {
        viewModelScope.launch {
            val result = sessionController.navigateTreeToEntry(entryId)
            if (result.isSuccess) {
                val navigation = result.getOrNull() ?: return@launch
                if (navigation.cancelled) {
                    _uiState.update {
                        it.copy(
                            isTreeSheetVisible = false,
                            errorMessage = "Tree navigation was cancelled",
                        )
                    }
                    return@launch
                }

                _uiState.update { state ->
                    val updatedTree =
                        state.sessionTree?.let { snapshot ->
                            if (navigation.sessionPath == null || navigation.sessionPath == snapshot.sessionPath) {
                                snapshot.copy(currentLeafId = navigation.currentLeafId)
                            } else {
                                snapshot
                            }
                        }

                    state.copy(
                        isTreeSheetVisible = false,
                        inputText = navigation.editorText.orEmpty(),
                        sessionTree = updatedTree,
                    )
                }
                loadInitialMessages(reason = TimelineReloadReason.TREE_NAVIGATION)
            } else {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    @Suppress("LongMethod")
    private fun loadSessionTree() {
        treeLoadJob?.cancel()
        val requestGeneration = sessionController.activeSession.value?.generation
        treeLoadJob =
            viewModelScope.launch {
                _uiState.update { it.copy(isLoadingTree = true) }

                val activePath = sessionController.activeSession.value?.sessionPath
                val stateResult =
                    if (!activePath.isNullOrBlank()) {
                        Result.success<RpcResponse?>(null)
                    } else {
                        sessionController.getState()
                    }
                if (stateResult.isFailure) {
                    _uiState.update {
                        it.copy(
                            isLoadingTree = false,
                            treeErrorMessage = stateResult.exceptionOrNull()?.message ?: "Failed to load session state",
                        )
                    }
                    return@launch
                }

                if (
                    requestGeneration != null &&
                    sessionController.activeSession.value?.generation != requestGeneration
                ) {
                    return@launch
                }

                val sessionPath = activePath ?: stateResult.getOrNull()?.data?.stringField("sessionFile")
                if (sessionPath.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(
                            isLoadingTree = false,
                            treeErrorMessage = "No active session path available",
                        )
                    }
                    return@launch
                }

                val filter = _uiState.value.treeFilter
                val treeStartedAt = System.currentTimeMillis()
                val result = sessionController.getSessionTree(sessionPath = sessionPath, filter = filter)
                recordMetricsSafely {
                    PerformanceMetrics.recordOperation(
                        operation = "chat_tree_response",
                        durationMs = System.currentTimeMillis() - treeStartedAt,
                    )
                }
                if (
                    requestGeneration != null &&
                    sessionController.activeSession.value?.generation != requestGeneration
                ) {
                    return@launch
                }
                _uiState.update { state ->
                    if (result.isSuccess) {
                        state.copy(
                            sessionTree = result.getOrNull(),
                            isLoadingTree = false,
                            treeErrorMessage = null,
                        )
                    } else {
                        state.copy(
                            isLoadingTree = false,
                            treeErrorMessage = result.exceptionOrNull()?.message ?: "Failed to load session tree",
                        )
                    }
                }
            }
    }

    private fun handleToolStart(event: ToolExecutionStartEvent) {
        val arguments = extractToolArguments(event.args)
        val editDiff = if (event.toolName == "edit") extractEditDiff(event.args) else null

        val nextItem =
            ChatTimelineItem.Tool(
                id = "tool-${event.toolCallId}",
                toolName = event.toolName,
                output = "Running…",
                isCollapsed = true,
                isStreaming = true,
                isError = false,
                arguments = arguments,
                editDiff = editDiff,
            )

        upsertTimelineItem(nextItem)
    }

    private fun handleToolUpdate(event: ToolExecutionUpdateEvent) {
        val throttler =
            toolUpdateThrottlers.getOrPut(event.toolCallId) {
                UiUpdateThrottler(TOOL_UPDATE_THROTTLE_MS)
            }

        throttler.offer(event)?.let(::applyToolUpdate)
            ?: scheduleToolUpdateFlush(event.toolCallId)
    }

    private fun applyToolUpdate(event: ToolExecutionUpdateEvent) {
        val output = extractToolOutput(event.partialResult)
        val itemId = "tool-${event.toolCallId}"
        val isCollapsed = output.length > TOOL_COLLAPSE_THRESHOLD
        val existingTool = _uiState.value.timeline.find { it.id == itemId } as? ChatTimelineItem.Tool

        val nextItem =
            ChatTimelineItem.Tool(
                id = itemId,
                toolName = event.toolName,
                output = output,
                isCollapsed = isCollapsed,
                isStreaming = true,
                isError = false,
                arguments = existingTool?.arguments ?: emptyMap(),
                editDiff = existingTool?.editDiff,
            )

        upsertTimelineItem(nextItem)
    }

    private fun handleToolEnd(event: ToolExecutionEndEvent) {
        val output = extractToolOutput(event.result)
        val itemId = "tool-${event.toolCallId}"
        val isCollapsed = output.length > TOOL_COLLAPSE_THRESHOLD
        val existingTool = _uiState.value.timeline.find { it.id == itemId } as? ChatTimelineItem.Tool

        val nextItem =
            ChatTimelineItem.Tool(
                id = itemId,
                toolName = event.toolName,
                output = output,
                isCollapsed = isCollapsed,
                isStreaming = false,
                isError = event.isError,
                arguments = existingTool?.arguments ?: emptyMap(),
                editDiff = existingTool?.editDiff,
            )

        upsertTimelineItem(nextItem)
    }

    private fun scheduleAssistantUpdateFlush() {
        if (assistantUpdateFlushJob?.isActive == true) return
        assistantUpdateFlushJob =
            viewModelScope.launch {
                delay(ASSISTANT_UPDATE_THROTTLE_MS)
                flushPendingAssistantUpdate(force = true)
            }
    }

    private fun flushPendingAssistantUpdate(force: Boolean) {
        val update =
            if (force) {
                assistantUpdateThrottler.flushPending()
            } else {
                assistantUpdateThrottler.drainReady()
            }

        if (update != null) {
            applyAssistantUpdate(
                update = update,
                source = AssistantUpdateSource.FLUSHED_DELTA,
            )
        }

        if (!assistantUpdateThrottler.hasPending()) {
            assistantUpdateFlushJob?.cancel()
            assistantUpdateFlushJob = null
        }
    }

    private fun scheduleToolUpdateFlush(toolCallId: String) {
        val existingJob = toolUpdateFlushJobs[toolCallId]
        if (existingJob?.isActive == true) return

        toolUpdateFlushJobs[toolCallId] =
            viewModelScope.launch {
                delay(TOOL_UPDATE_THROTTLE_MS)
                flushPendingToolUpdate(toolCallId = toolCallId, force = true)
            }
    }

    private fun flushPendingToolUpdate(
        toolCallId: String,
        force: Boolean,
    ) {
        val throttler = toolUpdateThrottlers[toolCallId] ?: return
        val update =
            if (force) {
                throttler.flushPending()
            } else {
                throttler.drainReady()
            }

        if (update != null) {
            applyToolUpdate(update)
        }

        if (!throttler.hasPending()) {
            toolUpdateFlushJobs.remove(toolCallId)?.cancel()
        }
    }

    private fun clearToolUpdateThrottle(toolCallId: String) {
        toolUpdateFlushJobs.remove(toolCallId)?.cancel()
        toolUpdateThrottlers.remove(toolCallId)
    }

    private fun flushAllPendingStreamUpdates(force: Boolean) {
        flushPendingAssistantUpdate(force = force)
        toolUpdateThrottlers.keys.toList().forEach { toolCallId ->
            flushPendingToolUpdate(toolCallId = toolCallId, force = force)
        }
    }

    private fun resetStreamingUpdateState() {
        assistantUpdateFlushJob?.cancel()
        assistantUpdateFlushJob = null
        assistantUpdateThrottler.reset()

        toolUpdateFlushJobs.values.forEach { it.cancel() }
        toolUpdateFlushJobs.clear()
        toolUpdateThrottlers.values.forEach { throttler -> throttler.reset() }
        toolUpdateThrottlers.clear()
    }

    private fun clearStreamingTimelineFlags() {
        if (
            fullTimeline.none { item ->
                when (item) {
                    is ChatTimelineItem.Assistant -> item.isStreaming
                    is ChatTimelineItem.Tool -> item.isStreaming
                    is ChatTimelineItem.User -> false
                }
            }
        ) {
            return
        }

        fullTimeline =
            fullTimeline.map { item ->
                when (item) {
                    is ChatTimelineItem.Assistant -> {
                        if (item.isStreaming) {
                            item.copy(isStreaming = false)
                        } else {
                            item
                        }
                    }

                    is ChatTimelineItem.Tool -> {
                        if (item.isStreaming) {
                            item.copy(isStreaming = false)
                        } else {
                            item
                        }
                    }

                    is ChatTimelineItem.User -> item
                }
            }

        publishVisibleTimeline()
    }

    private fun upsertTimelineItem(item: ChatTimelineItem) {
        val timelineState = ChatUiState(timeline = fullTimeline)
        fullTimeline =
            ChatTimelineReducer.upsertTimelineItem(
                state = timelineState,
                item = item,
                maxTimelineItems = MAX_TIMELINE_ITEMS,
            ).timeline

        if (visibleTimelineSize == 0) {
            visibleTimelineSize = minOf(fullTimeline.size, INITIAL_TIMELINE_SIZE)
        }

        publishVisibleTimeline()
    }

    private fun replacePendingUserItemOrUpsert(userItem: ChatTimelineItem.User) {
        val pendingIndex = consumeNextPendingLocalUserIndex() ?: findMatchingPendingUserIndex(userItem)

        if (pendingIndex == null) {
            upsertTimelineItem(userItem)
            return
        }

        val pendingItem = fullTimeline[pendingIndex] as ChatTimelineItem.User
        val mergedUserItem =
            userItem.copy(
                imageCount = maxOf(userItem.imageCount, pendingItem.imageCount),
                images = userItem.images.ifEmpty { pendingItem.images },
            )

        fullTimeline =
            fullTimeline.toMutableList().also { timeline ->
                timeline[pendingIndex] = mergedUserItem
            }

        publishVisibleTimeline()
    }

    private fun consumeNextPendingLocalUserIndex(): Int? {
        while (pendingLocalUserIds.isNotEmpty()) {
            val pendingId = pendingLocalUserIds.removeFirst()
            val index = fullTimeline.indexOfFirst { it.id == pendingId }
            if (index >= 0) {
                return index
            }
        }

        return null
    }

    private fun findMatchingPendingUserIndex(userItem: ChatTimelineItem.User): Int? {
        val fallbackIndex =
            fullTimeline.indexOfLast { item ->
                item is ChatTimelineItem.User &&
                    item.id.startsWith(LOCAL_USER_ITEM_PREFIX) &&
                    item.text == userItem.text &&
                    item.imageCount >= userItem.imageCount
            }

        if (fallbackIndex < 0) {
            return null
        }

        val pendingItemId = fullTimeline[fallbackIndex].id
        pendingLocalUserIds.remove(pendingItemId)
        return fallbackIndex
    }

    private fun discardPendingLocalUserItem(itemId: String) {
        pendingLocalUserIds.remove(itemId)
        removeTimelineItemById(itemId)
    }

    private fun removeTimelineItemById(itemId: String) {
        val existingIndex = fullTimeline.indexOfFirst { it.id == itemId }
        if (existingIndex < 0) return

        fullTimeline =
            fullTimeline.toMutableList().also { timeline ->
                timeline.removeAt(existingIndex)
            }

        if (visibleTimelineSize > fullTimeline.size) {
            visibleTimelineSize = fullTimeline.size
        }

        publishVisibleTimeline()
    }

    private fun setInitialTimeline(history: List<ChatTimelineItem>) {
        fullTimeline =
            ChatTimelineReducer.limitTimeline(
                timeline = history,
                maxTimelineItems = MAX_TIMELINE_ITEMS,
            )
        visibleTimelineSize = minOf(fullTimeline.size, INITIAL_TIMELINE_SIZE)
    }

    private fun mergeHistoryWithRealtimeTimeline(history: List<ChatTimelineItem>): List<ChatTimelineItem> {
        val realtimeItems = fullTimeline.filterNot { item -> item.id.startsWith(HISTORY_ITEM_PREFIX) }
        return if (realtimeItems.isEmpty()) {
            history
        } else {
            history + realtimeItems
        }
    }

    private fun updateTimelineState(transform: (ChatUiState) -> ChatUiState) {
        val timelineState = ChatUiState(timeline = fullTimeline)
        fullTimeline = transform(timelineState).timeline
        publishVisibleTimeline()
    }

    private fun publishVisibleTimeline() {
        val visible = visibleTimeline()
        val activeToolIds =
            fullTimeline
                .filterIsInstance<ChatTimelineItem.Tool>()
                .mapTo(mutableSetOf()) { tool -> tool.id }

        _uiState.update { state ->
            state.copy(
                timeline = visible,
                hasOlderMessages = hasOlderMessages(),
                hiddenHistoryCount = hiddenHistoryCount(),
                expandedToolArguments = state.expandedToolArguments.intersect(activeToolIds),
            )
        }
    }

    private fun visibleTimeline(): List<ChatTimelineItem> {
        if (fullTimeline.isEmpty()) return emptyList()
        return fullTimeline.drop(visibleTimelineStartIndex())
    }

    private fun visibleTimelineStartIndex(): Int {
        val visibleCount = visibleTimelineSize.coerceIn(0, fullTimeline.size)
        var startIndex = fullTimeline.size - visibleCount
        while (startIndex > 0 && fullTimeline[startIndex] !is ChatTimelineItem.User) {
            startIndex -= 1
        }
        return startIndex
    }

    private fun hasOlderMessages(): Boolean {
        return historyParsedStartIndex > 0 || visibleTimelineStartIndex() > 0
    }

    private fun hiddenHistoryCount(): Int {
        return visibleTimelineStartIndex() + historyParsedStartIndex
    }

    fun addImage(pendingImage: PendingImage) {
        if (pendingImage.sizeBytes > ImageEncoder.MAX_IMAGE_SIZE_BYTES) {
            _uiState.update { it.copy(errorMessage = "Image too large (max 5MB)") }
            return
        }
        _uiState.update { state ->
            state.copy(pendingImages = state.pendingImages + pendingImage)
        }
    }

    fun removeImage(index: Int) {
        _uiState.update { state ->
            state.copy(
                pendingImages = state.pendingImages.filterIndexed { i, _ -> i != index },
            )
        }
        applyDeferredFreshnessRefreshIfIdle()
    }

    private data class SlashCommandInvocation(
        val name: String,
        val args: String?,
    )

    private enum class AssistantUpdateSource {
        IMMEDIATE_DELTA,
        FLUSHED_DELTA,
        NON_DELTA,
    }

    private enum class TimelineReloadReason {
        INITIAL,
        CONNECTION_RECOVERY,
        SESSION_CHANGED,
        TREE_NAVIGATION,
        MANUAL_SYNC,
        AUTO_FRESHNESS_REFRESH,
    }

    private enum class FreshnessCheckTrigger {
        POLL,
        POST_LOAD,
    }

    override fun onCleared() {
        initialLoadJob?.cancel()
        stopSessionFreshnessMonitor()
        logThinkingDiagnostics(reason = "viewmodel_cleared")
        logStreamingDiagnostics(reason = "viewmodel_cleared")
        resetStreamingUpdateState()
        resetThinkingDiagnostics(startNewRun = false)
        resetStreamingDiagnostics(startNewRun = false)
        resetFreshnessWarningThrottle()
        pendingLocalUserIds.clear()
        super.onCleared()
    }

    companion object {
        const val TREE_FILTER_DEFAULT = "default"
        const val TREE_FILTER_ALL = "all"
        const val TREE_FILTER_NO_TOOLS = "no-tools"
        const val TREE_FILTER_USER_ONLY = "user-only"
        const val TREE_FILTER_LABELED_ONLY = "labeled-only"

        const val COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED = "builtin-bridge-backed"
        const val COMMAND_SOURCE_BUILTIN_UNSUPPORTED = "builtin-unsupported"

        const val DELIVERY_MODE_ALL = "all"
        const val DELIVERY_MODE_ONE_AT_A_TIME = "one-at-a-time"

        private const val BUILTIN_SETTINGS_COMMAND = "settings"
        private const val BUILTIN_TREE_COMMAND = "tree"
        private const val BUILTIN_STATS_COMMAND = "stats"
        private const val BUILTIN_MODEL_COMMAND = "model"
        private const val BUILTIN_SESSION_COMMAND = "session"
        private const val BUILTIN_COMPACT_COMMAND = "compact"
        private const val BUILTIN_EXPORT_COMMAND = "export"
        private const val BUILTIN_IMPORT_COMMAND = "import"
        private const val BUILTIN_FORK_COMMAND = "fork"
        private const val BUILTIN_NEW_COMMAND = "new"
        private const val BUILTIN_NAME_COMMAND = "name"
        private const val BUILTIN_RESUME_COMMAND = "resume"
        private const val BUILTIN_COPY_COMMAND = "copy"
        private const val BUILTIN_SHARE_COMMAND = "share"
        private const val BUILTIN_RELOAD_COMMAND = "reload"
        private const val BUILTIN_CHANGELOG_COMMAND = "changelog"
        private const val BUILTIN_SCOPED_MODELS_COMMAND = "scoped-models"
        private const val BUILTIN_HOTKEYS_COMMAND = "hotkeys"

        private const val INTERNAL_TREE_NAVIGATION_COMMAND = "pi-mobile-tree"
        private const val INTERNAL_STATS_WORKFLOW_COMMAND = "pi-mobile-open-stats"
        private const val INTERNAL_WORKFLOW_STATUS_KEY = "pi-mobile-workflow-action"
        private const val INTERNAL_WORKFLOW_ACTION_OPEN_STATS = "open_stats"

        private val BUILTIN_COMMANDS =
            listOf(
                SlashCommandInfo(
                    name = BUILTIN_SETTINGS_COMMAND,
                    description = "Open mobile settings tab",
                    source = COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
                SlashCommandInfo(
                    name = BUILTIN_TREE_COMMAND,
                    description = "Open session tree sheet",
                    source = COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
                SlashCommandInfo(
                    name = BUILTIN_STATS_COMMAND,
                    description = "Open session stats sheet",
                    source = COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
                SlashCommandInfo(
                    name = BUILTIN_MODEL_COMMAND,
                    description = "Open model picker",
                    source = COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
                SlashCommandInfo(
                    name = BUILTIN_SESSION_COMMAND,
                    description = "Open session stats overview",
                    source = COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
                SlashCommandInfo(
                    name = BUILTIN_COMPACT_COMMAND,
                    description = "Compact the active session context",
                    source = COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
                SlashCommandInfo(
                    name = BUILTIN_EXPORT_COMMAND,
                    description = "Export session to HTML",
                    source = COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
                SlashCommandInfo(
                    name = BUILTIN_IMPORT_COMMAND,
                    description = "Import a JSONL session from this device",
                    source = COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
                SlashCommandInfo(
                    name = BUILTIN_COPY_COMMAND,
                    description = "Copy the last assistant response",
                    source = COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
                SlashCommandInfo(
                    name = BUILTIN_FORK_COMMAND,
                    description = "Open tree and fork from a selected entry",
                    source = COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
                SlashCommandInfo(
                    name = BUILTIN_NEW_COMMAND,
                    description = "Start a new session",
                    source = COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
                SlashCommandInfo(
                    name = BUILTIN_RESUME_COMMAND,
                    description = "Not available in chat on mobile (use Sessions tab)",
                    source = COMMAND_SOURCE_BUILTIN_UNSUPPORTED,
                    location = null,
                    path = null,
                ),
                SlashCommandInfo(
                    name = BUILTIN_HOTKEYS_COMMAND,
                    description = "Not available on mobile yet",
                    source = COMMAND_SOURCE_BUILTIN_UNSUPPORTED,
                    location = null,
                    path = null,
                ),
            )

        private val BUILTIN_COMMAND_NAMES = BUILTIN_COMMANDS.map { it.name }.toSet()
        private val KNOWN_SLASH_COMMAND_NAMES =
            BUILTIN_COMMAND_NAMES +
                setOf(
                    BUILTIN_NAME_COMMAND,
                    BUILTIN_SHARE_COMMAND,
                    BUILTIN_RELOAD_COMMAND,
                    BUILTIN_CHANGELOG_COMMAND,
                    BUILTIN_SCOPED_MODELS_COMMAND,
                )
        private val INTERNAL_HIDDEN_COMMAND_NAMES =
            setOf(
                INTERNAL_TREE_NAVIGATION_COMMAND,
                INTERNAL_STATS_WORKFLOW_COMMAND,
            )

        private val SLASH_COMMAND_TOKEN_REGEX = Regex("^/([a-zA-Z0-9:_-]*)$")

        private const val HISTORY_ITEM_PREFIX = "history-"
        private const val LOCAL_USER_ITEM_PREFIX = "local-user-"
        private const val ASSISTANT_UPDATE_THROTTLE_MS = 80L
        private const val TOOL_UPDATE_THROTTLE_MS = 100L
        private const val TOOL_COLLAPSE_THRESHOLD = 400
        private const val MAX_TIMELINE_ITEMS = HISTORY_WINDOW_MAX_ITEMS
        private const val INITIAL_TIMELINE_SIZE = 120
        private const val TIMELINE_PAGE_SIZE = 120
        private const val BASH_HISTORY_SIZE = 10
        private const val MAX_NOTIFICATIONS = 6
        private const val MAX_PENDING_QUEUE_ITEMS = 20
        private const val THINKING_DIAGNOSTICS_LOG_TAG = "ThinkingDiagnostics"
        private const val STREAMING_DIAGNOSTICS_LOG_TAG = "StreamingDiagnostics"
        private const val SESSION_COHERENCY_WARNING_MESSAGE =
            "Potential cross-device session edits detected. Use Sync now before continuing."

        // Safety fallback for edits made outside the bridge; bridge-observed mutations resync separately.
        private const val SESSION_FRESHNESS_POLL_INTERVAL_MS = 60_000L
        private const val SESSION_FRESHNESS_WARNING_COOLDOWN_MS = 20_000L
        private const val LOCAL_SESSION_MUTATION_GRACE_MS = 90_000L
    }
}
