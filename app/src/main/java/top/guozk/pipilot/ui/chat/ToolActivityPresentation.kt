package top.guozk.pipilot.ui.chat

import top.guozk.pipilot.chat.ChatTimelineItem

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
        title = tool.toolName.ifBlank { "工具" },
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
        "read" -> target?.let { "读取 $it" } ?: "读取内容"
        "edit" -> target?.let { "编辑 $it" } ?: "编辑内容"
        "write" -> target?.let { "写入 $it" } ?: "写入内容"
        "bash" -> arguments["command"]?.lineSequence()?.firstOrNull()?.take(COMMAND_SUMMARY_LENGTH) ?: "执行命令"
        else -> toolName.ifBlank { "工具活动" }
    }
}

private fun Map<String, String>.toolTarget(): String? =
    listOf("path", "file_path", "filePath", "target").firstNotNullOfOrNull { key ->
        this[key]?.takeIf { it.isNotBlank() }
    }

private const val COMMAND_SUMMARY_LENGTH = 100
