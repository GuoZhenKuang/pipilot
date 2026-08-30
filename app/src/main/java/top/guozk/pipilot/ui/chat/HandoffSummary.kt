package top.guozk.pipilot.ui.chat

data class HandoffSummaryData(
    val sessionName: String?,
    val cwd: String?,
    val sessionPath: String?,
    val model: String?,
    val runStatus: HandoffRunStatus,
)

enum class HandoffRunStatus(val label: String) {
    WORKING("Pi 正在工作"),
    RETRYING("Pi 正在重试"),
    IDLE("Pi 处于空闲状态"),
}

fun formatHandoffSummary(data: HandoffSummaryData): String =
    buildList {
        add("PiPilot 交接摘要")
        data.sessionName?.takeIf { it.isNotBlank() }?.let { add("会话：$it") }
        data.cwd?.takeIf { it.isNotBlank() }?.let { add("工作目录：$it") }
        data.sessionPath?.takeIf { it.isNotBlank() }?.let { add("会话文件：$it") }
        data.model?.takeIf { it.isNotBlank() }?.let { add("模型：$it") }
        add("状态：${data.runStatus.label}")
    }.joinToString("\n")
