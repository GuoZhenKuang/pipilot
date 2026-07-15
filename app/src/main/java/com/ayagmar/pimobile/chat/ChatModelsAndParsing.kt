package com.ayagmar.pimobile.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corerpc.AvailableModel
import com.ayagmar.pimobile.corerpc.SessionStats
import com.ayagmar.pimobile.sessions.ModelInfo
import com.ayagmar.pimobile.sessions.SessionController
import com.ayagmar.pimobile.sessions.SessionTreeSnapshot
import com.ayagmar.pimobile.sessions.SlashCommandInfo
import kotlinx.serialization.json.JsonObject

data class ChatUiState(
    val isLoading: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isStreaming: Boolean = false,
    val isRetrying: Boolean = false,
    val timeline: List<ChatTimelineItem> = emptyList(),
    val hasOlderMessages: Boolean = false,
    val hiddenHistoryCount: Int = 0,
    val inputText: String = "",
    val errorMessage: String? = null,
    val currentModel: ModelInfo? = null,
    val thinkingLevel: String? = null,
    val sessionName: String? = null,
    val sessionPath: String? = null,
    val pendingMessageCount: Int = 0,
    val activeExtensionRequest: ExtensionUiRequest? = null,
    val notifications: List<ExtensionNotification> = emptyList(),
    val extensionWidgets: Map<String, ExtensionWidget> = emptyMap(),
    val extensionStatuses: Map<String, String> = emptyMap(),
    val extensionTitle: String? = null,
    val isCommandPaletteVisible: Boolean = false,
    val isCommandPaletteAutoOpened: Boolean = false,
    val commands: List<SlashCommandInfo> = emptyList(),
    val commandsQuery: String = "",
    val isLoadingCommands: Boolean = false,
    val steeringMode: String = ChatViewModel.DELIVERY_MODE_ONE_AT_A_TIME,
    val followUpMode: String = ChatViewModel.DELIVERY_MODE_ONE_AT_A_TIME,
    val pendingQueueItems: List<PendingQueueItem> = emptyList(),
    val pendingClipboardText: String? = null,
    val pendingImportRequestToken: String? = null,
    val isSyncingSession: Boolean = false,
    val sessionCoherencyWarning: String? = null,
    // Bash dialog state
    val isBashDialogVisible: Boolean = false,
    val bashCommand: String = "",
    val bashOutput: String = "",
    val bashExitCode: Int? = null,
    val isBashExecuting: Boolean = false,
    val bashWasTruncated: Boolean = false,
    val bashFullLogPath: String? = null,
    val bashHistory: List<String> = emptyList(),
    // Tool argument expansion state (per tool ID)
    val expandedToolArguments: Set<String> = emptySet(),
    // Session stats state
    val isStatsSheetVisible: Boolean = false,
    val sessionStats: SessionStats? = null,
    val isLoadingStats: Boolean = false,
    // Model picker state
    val isModelPickerVisible: Boolean = false,
    val availableModels: List<AvailableModel> = emptyList(),
    val modelsQuery: String = "",
    val isLoadingModels: Boolean = false,
    // Session tree state
    val isTreeSheetVisible: Boolean = false,
    val treeFilter: String = ChatViewModel.TREE_FILTER_DEFAULT,
    val sessionTree: SessionTreeSnapshot? = null,
    val isLoadingTree: Boolean = false,
    val treeErrorMessage: String? = null,
    // Image attachments
    val pendingImages: List<PendingImage> = emptyList(),
)

data class PendingImage(
    val uri: String,
    val mimeType: String,
    val sizeBytes: Long,
    val displayName: String?,
)

data class PendingQueueItem(
    val id: String,
    val type: PendingQueueType,
    val message: String,
    val mode: String,
)

enum class PendingQueueType {
    STEER,
    FOLLOW_UP,
}

data class ExtensionNotification(
    val message: String,
    val type: String,
)

data class ExtensionWidget(
    val lines: List<String>,
    val placement: String,
)

sealed interface ExtensionUiRequest {
    val requestId: String

    data class Select(
        override val requestId: String,
        val title: String,
        val options: List<String>,
    ) : ExtensionUiRequest

    data class Confirm(
        override val requestId: String,
        val title: String,
        val message: String,
    ) : ExtensionUiRequest

    data class Input(
        override val requestId: String,
        val title: String,
        val placeholder: String?,
    ) : ExtensionUiRequest

    data class Editor(
        override val requestId: String,
        val title: String,
        val prefill: String,
    ) : ExtensionUiRequest
}

sealed interface ChatTimelineItem {
    val id: String

    data class User(
        override val id: String,
        val text: String,
        val imageCount: Int = 0,
        val images: List<ChatImageSource> = emptyList(),
    ) : ChatTimelineItem

    data class Assistant(
        override val id: String,
        val text: String,
        val thinking: String? = null,
        val isThinkingExpanded: Boolean = false,
        val isThinkingComplete: Boolean = false,
        val isStreaming: Boolean,
    ) : ChatTimelineItem

    data class Tool(
        override val id: String,
        val toolName: String,
        val output: String,
        val isCollapsed: Boolean,
        val isStreaming: Boolean,
        val isError: Boolean,
        val arguments: Map<String, String> = emptyMap(),
        val editDiff: EditDiffInfo? = null,
        val isDiffExpanded: Boolean = false,
    ) : ChatTimelineItem
}

sealed interface ChatImageSource {
    data class LocalUri(val uri: String) : ChatImageSource

    data class Embedded(
        val base64Data: String,
        val mimeType: String,
    ) : ChatImageSource
}

/**
 * Information about a file edit for diff display.
 */
data class EditDiffInfo(
    val path: String,
    val oldString: String,
    val newString: String,
)

class ChatViewModelFactory(
    private val sessionController: SessionController,
    private val imageEncoder: ImageEncoder? = null,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        check(modelClass == ChatViewModel::class.java) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }

        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(
            sessionController = sessionController,
            imageEncoder = imageEncoder,
        ) as T
    }
}

internal data class InitialLoadMetadata(
    val modelInfo: ModelInfo?,
    val thinkingLevel: String?,
    val isStreaming: Boolean,
    val steeringMode: String,
    val followUpMode: String,
    val sessionPath: String?,
    val sessionName: String?,
    val pendingMessageCount: Int,
)

internal data class DraftClearState(
    val inputWasCleared: Boolean,
    val imagesWereCleared: Boolean,
)

internal data class ThinkingDiagnosticsCounters(
    val startEvents: Int = 0,
    val deltaEvents: Int = 0,
    val deltaChars: Int = 0,
    val endEvents: Int = 0,
    val endPayloadEvents: Int = 0,
    val endPayloadChars: Int = 0,
    val renderedThinkingUpdates: Int = 0,
    val renderedThinkingCompleteEvents: Int = 0,
)

internal data class StreamingDeltaDiagnosticsCounters(
    val messageUpdateEvents: Int = 0,
    val assistantDeltaEvents: Int = 0,
    val textDeltaEvents: Int = 0,
    val thinkingDeltaEvents: Int = 0,
    val assistantNonDeltaEvents: Int = 0,
    val coalescedDeltaEvents: Int = 0,
    val emittedImmediateDeltaEvents: Int = 0,
    val emittedFlushedDeltaEvents: Int = 0,
)

internal data class HistoryMessageWindow(
    val messages: List<JsonObject>,
    val absoluteOffset: Int,
)
