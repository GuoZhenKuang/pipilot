package com.ayagmar.pimobile.ui.chat

import com.ayagmar.pimobile.chat.ChatTimelineItem

data class ToolActivityPresentation(
    val title: String,
    val summary: String,
    val status: ToolActivityStatus,
    val hasDetails: Boolean,
)

enum class ToolActivityStatus {
    RUNNING,
    SUCCESS,
    ERROR,
}

fun shouldCollapseSettledToolGroup(
    wasStreaming: Boolean,
    isStreaming: Boolean,
    hasError: Boolean,
): Boolean = wasStreaming && !isStreaming && !hasError

fun presentToolActivity(tool: ChatTimelineItem.Tool): ToolActivityPresentation =
    ToolActivityPresentation(
        title = tool.toolName.ifBlank { "Tool" },
        summary = tool.summary(),
        status = tool.status(),
        hasDetails = tool.output.isNotBlank() || tool.arguments.isNotEmpty() || tool.editDiff != null,
    )

private fun ChatTimelineItem.Tool.status(): ToolActivityStatus =
    when {
        isError -> ToolActivityStatus.ERROR
        isStreaming -> ToolActivityStatus.RUNNING
        else -> ToolActivityStatus.SUCCESS
    }

private fun ChatTimelineItem.Tool.summary(): String {
    val target = arguments.toolTarget()
    return when (toolName.lowercase()) {
        "read" -> target?.let { "Read $it" } ?: "Read content"
        "edit" -> target?.let { "Edited $it" } ?: "Edited content"
        "write" -> target?.let { "Wrote $it" } ?: "Wrote content"
        "bash" -> arguments["command"]?.lineSequence()?.firstOrNull()?.take(COMMAND_SUMMARY_LENGTH) ?: "Ran command"
        else -> toolName.ifBlank { "Tool activity" }
    }
}

private fun Map<String, String>.toolTarget(): String? =
    listOf("path", "file_path", "filePath", "target").firstNotNullOfOrNull { key ->
        this[key]?.takeIf { it.isNotBlank() }
    }

private const val COMMAND_SUMMARY_LENGTH = 100
