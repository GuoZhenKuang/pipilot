package com.ayagmar.pimobile.ui.chat

import android.content.ClipData
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayagmar.pimobile.chat.ChatTimelineItem
import com.ayagmar.pimobile.chat.ChatUiState
import com.ayagmar.pimobile.chat.ChatViewModel
import com.ayagmar.pimobile.chat.ChatViewModelFactory
import com.ayagmar.pimobile.chat.ImageEncoder
import com.ayagmar.pimobile.chat.PendingImage
import com.ayagmar.pimobile.corerpc.AvailableModel
import com.ayagmar.pimobile.perf.StreamingFrameMetrics
import com.ayagmar.pimobile.sessions.ModelInfo
import com.ayagmar.pimobile.sessions.SessionController
import com.ayagmar.pimobile.sessions.SlashCommandInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun readTextFromUri(
    context: android.content.Context,
    uri: Uri,
): String? {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader().use { reader ->
            reader?.readText()
        }
    }.getOrNull()
}

private fun resolveDocumentDisplayName(
    context: android.content.Context,
    uri: Uri,
): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use null
            }

            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex == -1) {
                null
            } else {
                cursor.getString(nameIndex)
            }
        }
    }.getOrNull()
}

internal data class ChatCallbacks(
    val onToggleToolExpansion: (String) -> Unit,
    val onDismissToolDetails: () -> Unit,
    val onToggleThinkingExpansion: (String) -> Unit,
    val onToggleDiffExpansion: (String) -> Unit,
    val onToggleToolArgumentsExpansion: (String) -> Unit,
    val onLoadOlderMessages: () -> Unit,
    val onInputTextChanged: (String) -> Unit,
    val onSendPrompt: () -> Unit,
    val onAbort: () -> Unit,
    val onSteer: (String) -> Unit,
    val onFollowUp: (String) -> Unit,
    val onRemovePendingQueueItem: (String) -> Unit,
    val onClearPendingQueueItems: () -> Unit,
    val onSetThinkingLevel: (String) -> Unit,
    val onAbortRetry: () -> Unit,
    val onSendExtensionUiResponse: (String, String?, Boolean?, Boolean) -> Unit,
    val onDismissExtensionRequest: () -> Unit,
    val onClearNotification: (Int) -> Unit,
    val onShowCommandPalette: () -> Unit,
    val onHideCommandPalette: () -> Unit,
    val onCommandsQueryChanged: (String) -> Unit,
    val onCommandSelected: (SlashCommandInfo) -> Unit,
    val onCopyLastResponse: () -> Unit,
    val onExportSession: () -> Unit,
    // Bash callbacks
    val onShowBashDialog: () -> Unit,
    val onHideBashDialog: () -> Unit,
    val onBashCommandChanged: (String) -> Unit,
    val onExecuteBash: () -> Unit,
    val onAbortBash: () -> Unit,
    val onSelectBashHistory: (String) -> Unit,
    // Session stats callbacks
    val onShowStatsSheet: () -> Unit,
    val onHideStatsSheet: () -> Unit,
    val onRefreshStats: () -> Unit,
    // Model picker callbacks
    val onShowModelPicker: () -> Unit,
    val onHideModelPicker: () -> Unit,
    val onModelsQueryChanged: (String) -> Unit,
    val onSelectModel: (AvailableModel) -> Unit,
    val onSyncNow: () -> Unit,
    val onCompactSession: () -> Unit,
    // Tree navigation callbacks
    val onShowTreeSheet: () -> Unit,
    val onHideTreeSheet: () -> Unit,
    val onForkFromTreeEntry: (String) -> Unit,
    val onJumpAndContinueFromTreeEntry: (String) -> Unit,
    val onTreeFilterChanged: (String) -> Unit,
    // Image attachment callbacks
    val onAddImage: (PendingImage) -> Unit,
    val onRemoveImage: (Int) -> Unit,
)

internal data class PromptControlsCallbacks(
    val onInputTextChanged: (String) -> Unit,
    val onSendPrompt: () -> Unit,
    val onShowCommandPalette: () -> Unit,
    val onAddImage: (PendingImage) -> Unit,
    val onRemoveImage: (Int) -> Unit,
    val onAbort: () -> Unit,
    val onAbortRetry: () -> Unit,
    val onSteer: (String) -> Unit,
    val onFollowUp: (String) -> Unit,
    val onRemovePendingQueueItem: (String) -> Unit,
    val onClearPendingQueueItems: () -> Unit,
)

@Suppress("LongMethod")
@Composable
fun ChatRoute(
    sessionController: SessionController,
    showExtensionStatusStrip: Boolean,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val routeScope = rememberCoroutineScope()
    val imageEncoder = remember { ImageEncoder(context) }
    val factory =
        remember(sessionController, imageEncoder) {
            ChatViewModelFactory(
                sessionController = sessionController,
                imageEncoder = imageEncoder,
            )
        }
    val chatViewModel: ChatViewModel = viewModel(factory = factory)
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(
        lifecycleOwner,
        chatViewModel,
    ) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> chatViewModel.onChatActiveChanged(true)
                    Lifecycle.Event.ON_STOP -> chatViewModel.onChatActiveChanged(false)
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        chatViewModel.onChatActiveChanged(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            chatViewModel.onChatActiveChanged(false)
        }
    }
    val importSessionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) {
                return@rememberLauncherForActivityResult
            }

            routeScope.launch {
                val fileName = resolveDocumentDisplayName(context, uri) ?: "imported-session.jsonl"
                val jsonlContent =
                    withContext(Dispatchers.IO) {
                        readTextFromUri(context, uri)
                    }

                if (jsonlContent == null) {
                    chatViewModel.onImportSessionReadFailed("Failed to read selected JSONL file")
                    return@launch
                }

                chatViewModel.importSessionJsonl(
                    fileName = fileName,
                    jsonlContent = jsonlContent,
                )
            }
        }

    LaunchedEffect(uiState.pendingClipboardText) {
        val pendingText = uiState.pendingClipboardText ?: return@LaunchedEffect
        val copied =
            runCatching {
                clipboard.setClipEntry(
                    ClipEntry(ClipData.newPlainText("Pi Mobile", pendingText)),
                )
            }.isSuccess
        chatViewModel.consumePendingClipboardText(copySucceeded = copied)
    }

    LaunchedEffect(uiState.pendingImportRequestToken) {
        uiState.pendingImportRequestToken ?: return@LaunchedEffect
        chatViewModel.consumePendingImportRequest()
        importSessionLauncher.launch(arrayOf("*/*"))
    }

    val callbacks =
        remember(chatViewModel) {
            ChatCallbacks(
                onToggleToolExpansion = chatViewModel::toggleToolExpansion,
                onDismissToolDetails = chatViewModel::dismissToolDetails,
                onToggleThinkingExpansion = chatViewModel::toggleThinkingExpansion,
                onToggleDiffExpansion = chatViewModel::toggleDiffExpansion,
                onToggleToolArgumentsExpansion = chatViewModel::toggleToolArgumentsExpansion,
                onLoadOlderMessages = chatViewModel::loadOlderMessages,
                onInputTextChanged = chatViewModel::onInputTextChanged,
                onSendPrompt = chatViewModel::sendPrompt,
                onAbort = chatViewModel::abort,
                onSteer = chatViewModel::steer,
                onFollowUp = chatViewModel::followUp,
                onRemovePendingQueueItem = chatViewModel::removePendingQueueItem,
                onClearPendingQueueItems = chatViewModel::clearPendingQueueItems,
                onSetThinkingLevel = chatViewModel::setThinkingLevel,
                onAbortRetry = chatViewModel::abortRetry,
                onSendExtensionUiResponse = chatViewModel::sendExtensionUiResponse,
                onDismissExtensionRequest = chatViewModel::dismissExtensionRequest,
                onClearNotification = chatViewModel::clearNotification,
                onShowCommandPalette = chatViewModel::showCommandPalette,
                onHideCommandPalette = chatViewModel::hideCommandPalette,
                onCommandsQueryChanged = chatViewModel::onCommandsQueryChanged,
                onCommandSelected = chatViewModel::onCommandSelected,
                onCopyLastResponse = chatViewModel::copyLastResponse,
                onExportSession = chatViewModel::exportSession,
                onShowBashDialog = chatViewModel::showBashDialog,
                onHideBashDialog = chatViewModel::hideBashDialog,
                onBashCommandChanged = chatViewModel::onBashCommandChanged,
                onExecuteBash = chatViewModel::executeBash,
                onAbortBash = chatViewModel::abortBash,
                onSelectBashHistory = chatViewModel::selectBashHistoryItem,
                onShowStatsSheet = chatViewModel::showStatsSheet,
                onHideStatsSheet = chatViewModel::hideStatsSheet,
                onRefreshStats = chatViewModel::refreshSessionStats,
                onShowModelPicker = chatViewModel::showModelPicker,
                onHideModelPicker = chatViewModel::hideModelPicker,
                onModelsQueryChanged = chatViewModel::onModelsQueryChanged,
                onSelectModel = chatViewModel::selectModel,
                onSyncNow = chatViewModel::syncNow,
                onCompactSession = chatViewModel::compactNow,
                onShowTreeSheet = chatViewModel::showTreeSheet,
                onHideTreeSheet = chatViewModel::hideTreeSheet,
                onForkFromTreeEntry = chatViewModel::forkFromTreeEntry,
                onJumpAndContinueFromTreeEntry = chatViewModel::jumpAndContinueFromTreeEntry,
                onTreeFilterChanged = chatViewModel::setTreeFilter,
                onAddImage = chatViewModel::addImage,
                onRemoveImage = chatViewModel::removeImage,
            )
        }

    ChatScreen(
        state = uiState,
        cwd = sessionController.getActiveCwd(),
        callbacks = callbacks,
        showExtensionStatusStrip = showExtensionStatusStrip,
    )
}

@Suppress("LongMethod")
@Composable
private fun ChatScreen(
    state: ChatUiState,
    cwd: String?,
    callbacks: ChatCallbacks,
    showExtensionStatusStrip: Boolean,
) {
    StreamingFrameMetrics(
        isStreaming = state.isStreaming,
        onJankDetected = { droppedFrame ->
            Log.d(
                STREAMING_FRAME_LOG_TAG,
                "jank severity=${droppedFrame.severity} " +
                    "frame=${droppedFrame.frameTimeMs}ms dropped=${droppedFrame.expectedFrames}",
            )
        },
    )

    ChatScreenContent(
        state = state,
        callbacks = callbacks,
        showExtensionStatusStrip = showExtensionStatusStrip,
    )

    val selectedTool =
        state.timeline
            .filterIsInstance<ChatTimelineItem.Tool>()
            .firstOrNull { it.id == state.selectedToolId }
    ToolDetailsSheet(
        isVisible = selectedTool != null,
        tool = selectedTool,
        onDismiss = callbacks.onDismissToolDetails,
    )

    ExtensionUiDialogs(
        request = state.activeExtensionRequest,
        onSendResponse = callbacks.onSendExtensionUiResponse,
        onDismiss = callbacks.onDismissExtensionRequest,
    )

    NotificationsDisplay(
        notifications = state.notifications,
        onClear = callbacks.onClearNotification,
    )

    CommandPalette(
        isVisible = state.isCommandPaletteVisible,
        commands = state.commands,
        query = state.commandsQuery,
        isLoading = state.isLoadingCommands,
        onQueryChange = callbacks.onCommandsQueryChanged,
        onCommandSelected = callbacks.onCommandSelected,
        onDismiss = callbacks.onHideCommandPalette,
    )

    BashDialog(
        isVisible = state.isBashDialogVisible,
        command = state.bashCommand,
        output = state.bashOutput,
        exitCode = state.bashExitCode,
        isExecuting = state.isBashExecuting,
        wasTruncated = state.bashWasTruncated,
        fullLogPath = state.bashFullLogPath,
        history = state.bashHistory,
        onCommandChange = callbacks.onBashCommandChanged,
        onExecute = callbacks.onExecuteBash,
        onAbort = callbacks.onAbortBash,
        onSelectHistory = callbacks.onSelectBashHistory,
        onDismiss = callbacks.onHideBashDialog,
    )

    SessionStatsSheet(
        isVisible = state.isStatsSheetVisible,
        stats = state.sessionStats,
        sessionName = state.sessionName,
        cwd = cwd,
        model = state.currentModel,
        pendingMessageCount = state.pendingMessageCount,
        isRunActive = state.isStreaming || state.isRetrying,
        isRetrying = state.isRetrying,
        isLoading = state.isLoadingStats,
        onRefresh = callbacks.onRefreshStats,
        onSync = callbacks.onSyncNow,
        onCompact = callbacks.onCompactSession,
        onCopyLatestResponse = callbacks.onCopyLastResponse,
        onExportSession = callbacks.onExportSession,
        onDismiss = callbacks.onHideStatsSheet,
    )

    ModelPickerSheet(
        isVisible = state.isModelPickerVisible,
        models = state.availableModels,
        currentModel = state.currentModel,
        query = state.modelsQuery,
        isLoading = state.isLoadingModels,
        onQueryChange = callbacks.onModelsQueryChanged,
        onSelectModel = callbacks.onSelectModel,
        onDismiss = callbacks.onHideModelPicker,
    )

    TreeNavigationSheet(
        isVisible = state.isTreeSheetVisible,
        tree = state.sessionTree,
        selectedFilter = state.treeFilter,
        isLoading = state.isLoadingTree,
        errorMessage = state.treeErrorMessage,
        onFilterChange = callbacks.onTreeFilterChanged,
        onForkFromEntry = callbacks.onForkFromTreeEntry,
        onJumpAndContinue = callbacks.onJumpAndContinueFromTreeEntry,
        onDismiss = callbacks.onHideTreeSheet,
    )
}

@Suppress("LongMethod")
@Composable
private fun ChatScreenContent(
    state: ChatUiState,
    callbacks: ChatCallbacks,
    showExtensionStatusStrip: Boolean,
) {
    val hasStreamingTimelineItem =
        remember(state.timeline) {
            state.timeline.any { item ->
                when (item) {
                    is ChatTimelineItem.Assistant -> item.isStreaming
                    is ChatTimelineItem.Tool -> item.isStreaming
                    is ChatTimelineItem.User -> false
                }
            }
        }
    val isRunActive = state.isStreaming || state.isRetrying || hasStreamingTimelineItem

    var runStartedAtMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(isRunActive) {
        if (isRunActive) {
            if (runStartedAtMs == null) {
                runStartedAtMs = System.currentTimeMillis()
            }
        } else {
            runStartedAtMs = null
        }
    }

    val elapsedSeconds by
        produceState(
            initialValue = 0L,
            key1 = isRunActive,
            key2 = runStartedAtMs,
        ) {
            val startedAt = runStartedAtMs
            if (!isRunActive || startedAt == null) {
                value = 0L
                return@produceState
            }

            while (true) {
                value = ((System.currentTimeMillis() - startedAt).coerceAtLeast(0L)) / RUN_PROGRESS_TICK_MS
                delay(RUN_PROGRESS_TICK_MS)
            }
        }

    val runPhase =
        remember(state.isRetrying, state.timeline) {
            inferLiveRunPhase(
                isRetrying = state.isRetrying,
                timeline = state.timeline,
            )
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChatHeader(
            isRunActive = isRunActive,
            isSyncingSession = state.isSyncingSession,
            sessionCoherencyWarning = state.sessionCoherencyWarning,
            extensionTitle = state.extensionTitle,
            sessionName = state.sessionName,
            pendingMessageCount = state.pendingMessageCount,
            connectionState = state.connectionState,
            currentModel = state.currentModel,
            thinkingLevel = state.thinkingLevel,
            contextUsageLabel = formatContextUsageLabel(state.sessionStats, state.currentModel),
            errorMessage = state.errorMessage,
            callbacks = callbacks,
        )

        // Extension widgets (above editor)
        ExtensionWidgets(
            widgets = state.extensionWidgets,
            placement = "aboveEditor",
        )

        Box(modifier = Modifier.weight(1f)) {
            ChatBody(
                isLoading = state.isLoading,
                timeline = state.timeline,
                hasOlderMessages = state.hasOlderMessages,
                hiddenHistoryCount = state.hiddenHistoryCount,
                expandedToolArguments = state.expandedToolArguments,
                isRunActive = isRunActive,
                runPhase = runPhase,
                runElapsedSeconds = elapsedSeconds,
                callbacks = callbacks,
            )
        }

        // Extension widgets (below editor)
        ExtensionWidgets(
            widgets = state.extensionWidgets,
            placement = "belowEditor",
        )

        if (showExtensionStatusStrip) {
            ExtensionStatusStrip(statuses = state.extensionStatuses)
        }

        PromptControls(
            isStreaming = isRunActive,
            isRetrying = state.isRetrying,
            isDispatchingMessage = state.isDispatchingMessage,
            pendingQueueItems = state.pendingQueueItems,
            steeringMode = state.steeringMode,
            followUpMode = state.followUpMode,
            inputText = state.inputText,
            pendingImages = state.pendingImages,
            callbacks =
                PromptControlsCallbacks(
                    onInputTextChanged = callbacks.onInputTextChanged,
                    onSendPrompt = callbacks.onSendPrompt,
                    onShowCommandPalette = callbacks.onShowCommandPalette,
                    onAddImage = callbacks.onAddImage,
                    onRemoveImage = callbacks.onRemoveImage,
                    onAbort = callbacks.onAbort,
                    onAbortRetry = callbacks.onAbortRetry,
                    onSteer = callbacks.onSteer,
                    onFollowUp = callbacks.onFollowUp,
                    onRemovePendingQueueItem = callbacks.onRemovePendingQueueItem,
                    onClearPendingQueueItems = callbacks.onClearPendingQueueItems,
                ),
        )
    }
}

@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
private fun ChatHeader(
    isRunActive: Boolean,
    isSyncingSession: Boolean,
    sessionCoherencyWarning: String?,
    extensionTitle: String?,
    sessionName: String?,
    pendingMessageCount: Int,
    connectionState: com.ayagmar.pimobile.corenet.ConnectionState,
    currentModel: ModelInfo?,
    thinkingLevel: String?,
    contextUsageLabel: String,
    errorMessage: String?,
    callbacks: ChatCallbacks,
) {
    val isCompact = isRunActive
    var showSecondaryActionsMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Top row: Title and minimal actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val title = extensionTitle ?: sessionName ?: "Chat"
                Text(
                    text = title,
                    style =
                        if (isCompact) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.headlineSmall
                        },
                )

                if (!isCompact && extensionTitle == null) {
                    Text(
                        text = formatConnectionSummary(connectionState, pendingMessageCount),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            when (connectionState) {
                                com.ayagmar.pimobile.corenet.ConnectionState.CONNECTED ->
                                    MaterialTheme.colorScheme.primary
                                com.ayagmar.pimobile.corenet.ConnectionState.CONNECTING,
                                com.ayagmar.pimobile.corenet.ConnectionState.RECONNECTING,
                                -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.outline
                            },
                    )
                }
            }

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSyncingSession) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = callbacks.onSyncNow) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Sync now",
                        )
                    }
                }

                IconButton(onClick = { showSecondaryActionsMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More actions",
                    )
                }

                DropdownMenu(
                    expanded = showSecondaryActionsMenu,
                    onDismissRequest = { showSecondaryActionsMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Tree") },
                        onClick = {
                            showSecondaryActionsMenu = false
                            callbacks.onShowTreeSheet()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Bash") },
                        onClick = {
                            showSecondaryActionsMenu = false
                            callbacks.onShowBashDialog()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Session details") },
                        onClick = {
                            showSecondaryActionsMenu = false
                            callbacks.onShowStatsSheet()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy last response") },
                        onClick = {
                            showSecondaryActionsMenu = false
                            callbacks.onCopyLastResponse()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Compact now") },
                        onClick = {
                            showSecondaryActionsMenu = false
                            callbacks.onCompactSession()
                        },
                    )
                }
            }
        }

        sessionCoherencyWarning?.let { warning ->
            Text(
                text = warning,
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Compact model/thinking controls
        ModelThinkingControls(
            currentModel = currentModel,
            thinkingLevel = thinkingLevel,
            contextUsageLabel = contextUsageLabel,
            onSetThinkingLevel = callbacks.onSetThinkingLevel,
            onShowModelPicker = callbacks.onShowModelPicker,
            onShowStats = callbacks.onShowStatsSheet,
        )

        // Error message if any
        errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun formatConnectionSummary(
    connectionState: com.ayagmar.pimobile.corenet.ConnectionState,
    pendingMessageCount: Int,
): String {
    val statusLabel =
        when (connectionState) {
            com.ayagmar.pimobile.corenet.ConnectionState.CONNECTED -> "Connected"
            com.ayagmar.pimobile.corenet.ConnectionState.CONNECTING -> "Connecting"
            com.ayagmar.pimobile.corenet.ConnectionState.RECONNECTING -> "Reconnecting"
            com.ayagmar.pimobile.corenet.ConnectionState.DISCONNECTED -> "Offline"
        }

    if (pendingMessageCount <= 0) {
        return statusLabel
    }

    return "$statusLabel • ${formatQueuedMessagesLabel(pendingMessageCount)}"
}

private fun formatQueuedMessagesLabel(pendingMessageCount: Int): String {
    val suffix = if (pendingMessageCount == 1) "msg" else "msgs"
    return "Queued $pendingMessageCount $suffix"
}

@Composable
private fun LiveRunProgressIndicator(
    phase: LiveRunPhase,
    elapsedSeconds: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .testTag(CHAT_RUN_PROGRESS_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text =
                if (phase == LiveRunPhase.WORKING) {
                    "Working · waiting for activity · ${formatRunElapsed(elapsedSeconds)}"
                } else {
                    "Working · ${phase.label} · ${formatRunElapsed(elapsedSeconds)}"
                },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun InlineRunProgressCard(
    phase: LiveRunPhase,
    elapsedSeconds: Long,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Assistant",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            LiveRunProgressIndicator(
                phase = phase,
                elapsedSeconds = elapsedSeconds,
                modifier = Modifier,
            )
        }
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun BashDialog(
    isVisible: Boolean,
    command: String,
    output: String,
    exitCode: Int?,
    isExecuting: Boolean,
    wasTruncated: Boolean,
    fullLogPath: String?,
    history: List<String>,
    onCommandChange: (String) -> Unit,
    onExecute: () -> Unit,
    onAbort: () -> Unit,
    onSelectHistory: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isVisible) return

    var showHistoryDropdown by remember { mutableStateOf(false) }
    val copyToClipboard = rememberClipboardCopy()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!isExecuting) onDismiss() },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Run Bash Command")
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Command input with history dropdown
                Box {
                    OutlinedTextField(
                        value = command,
                        onValueChange = onCommandChange,
                        placeholder = { Text("Enter command...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isExecuting,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        trailingIcon = {
                            if (history.isNotEmpty() && !isExecuting) {
                                IconButton(onClick = { showHistoryDropdown = true }) {
                                    Icon(
                                        imageVector = Icons.Default.ExpandMore,
                                        contentDescription = "History",
                                    )
                                }
                            }
                        },
                    )

                    DropdownMenu(
                        expanded = showHistoryDropdown,
                        onDismissRequest = { showHistoryDropdown = false },
                    ) {
                        history.forEach { historyCommand ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = historyCommand,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                    )
                                },
                                onClick = {
                                    onSelectHistory(historyCommand)
                                    showHistoryDropdown = false
                                },
                            )
                        }
                    }
                }

                // Output display
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors =
                        androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Output",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (output.isNotEmpty()) {
                                IconButton(
                                    onClick = { copyToClipboard(output) },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy output",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }

                        SelectionContainer {
                            Text(
                                text = output.ifEmpty { "(no output)" },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }

                // Exit code and truncation info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (exitCode != null) {
                        val exitColor =
                            if (exitCode == 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = "Exit: $exitCode",
                                    color = exitColor,
                                )
                            },
                        )
                    }

                    if (wasTruncated && fullLogPath != null) {
                        TextButton(
                            onClick = { copyToClipboard(fullLogPath) },
                        ) {
                            Text(
                                text = "Output truncated (copy path)",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isExecuting) {
                Button(
                    onClick = onAbort,
                    colors =
                        androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).padding(end = 4.dp),
                    )
                    Text("Abort")
                }
            } else {
                Button(
                    onClick = onExecute,
                    enabled = command.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).padding(end = 4.dp),
                    )
                    Text("Execute")
                }
            }
        },
        dismissButton = {
            if (!isExecuting) {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
    )
}
