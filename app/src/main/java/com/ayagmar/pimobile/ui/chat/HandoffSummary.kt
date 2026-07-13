package com.ayagmar.pimobile.ui.chat

data class HandoffSummaryData(
    val sessionName: String?,
    val cwd: String?,
    val sessionPath: String?,
    val model: String?,
    val runStatus: HandoffRunStatus,
)

enum class HandoffRunStatus(val label: String) {
    WORKING("Pi is working"),
    WAITING("Pi is waiting"),
    IDLE("Pi is idle"),
}

fun formatHandoffSummary(data: HandoffSummaryData): String =
    buildList {
        add("Pi Mobile handoff")
        data.sessionName?.takeIf { it.isNotBlank() }?.let { add("Session: $it") }
        data.cwd?.takeIf { it.isNotBlank() }?.let { add("Working directory: $it") }
        data.sessionPath?.takeIf { it.isNotBlank() }?.let { add("Session file: $it") }
        data.model?.takeIf { it.isNotBlank() }?.let { add("Model: $it") }
        add("Status: ${data.runStatus.label}")
    }.joinToString("\n")
