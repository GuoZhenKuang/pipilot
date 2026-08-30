package com.ayagmar.pimobile.ui.chat

import com.ayagmar.pimobile.chat.ChatTimelineItem

data class TimelineReadingState(
    val sticksToBottom: Boolean = true,
    val assistantUnreadCount: Int = 0,
    val toolUnreadCount: Int = 0,
) {
    val unreadCount: Int
        get() = assistantUnreadCount + toolUnreadCount
}

data class TimelineUnreadDelta(
    val assistantCount: Int = 0,
    val toolCount: Int = 0,
) {
    val totalCount: Int
        get() = assistantCount + toolCount
}

sealed interface TimelineReadingAction {
    data object ScrollAway : TimelineReadingAction

    data object ReachBottom : TimelineReadingAction

    data class NewActivity(val delta: TimelineUnreadDelta) : TimelineReadingAction

    data object DisclosureChanged : TimelineReadingAction

    data object JumpToLatest : TimelineReadingAction
}

fun reduceTimelineReadingState(
    state: TimelineReadingState,
    action: TimelineReadingAction,
): TimelineReadingState =
    when (action) {
        TimelineReadingAction.ScrollAway -> state.copy(sticksToBottom = false)
        is TimelineReadingAction.NewActivity ->
            if (state.sticksToBottom || action.delta.totalCount <= 0) {
                state
            } else {
                state.copy(
                    assistantUnreadCount = state.assistantUnreadCount + action.delta.assistantCount,
                    toolUnreadCount = state.toolUnreadCount + action.delta.toolCount,
                )
            }
        TimelineReadingAction.ReachBottom,
        TimelineReadingAction.JumpToLatest,
        -> TimelineReadingState()
        TimelineReadingAction.DisclosureChanged -> state.copy(sticksToBottom = false)
    }

fun timelineActivityIdentities(timeline: List<ChatTimelineItem>): List<String> =
    timeline.mapNotNull { item ->
        when (item) {
            is ChatTimelineItem.Assistant ->
                if (item.text.isNotBlank() || !item.thinking.isNullOrBlank() || !item.isStreaming) {
                    "assistant:${item.id}"
                } else {
                    null
                }
            is ChatTimelineItem.Tool -> "tool:${item.id}"
            is ChatTimelineItem.User -> "user:${item.id}"
        }
    }

fun countNewTimelineActivities(
    previous: List<String>,
    current: List<String>,
): TimelineUnreadDelta {
    if (previous.isEmpty()) return TimelineUnreadDelta()
    val previousIdentities = previous.toHashSet()
    val previousTailIndex = current.indexOfLast { it == previous.last() }
    val appended = if (previousTailIndex >= 0) current.drop(previousTailIndex + 1) else current
    val newIdentities = appended.filter { it !in previousIdentities }
    return TimelineUnreadDelta(
        assistantCount = newIdentities.count { it.startsWith("assistant:") },
        toolCount = newIdentities.count { it.startsWith("tool:") },
    )
}

fun formatUnreadActivityLabel(state: TimelineReadingState): String =
    when {
        state.assistantUnreadCount > 0 && state.toolUnreadCount > 0 ->
            "${state.assistantUnreadCount} 条回复 · ${state.toolUnreadCount} 个工具"
        state.assistantUnreadCount > 0 ->
            "${state.assistantUnreadCount} 条回复"
        state.toolUnreadCount > 0 ->
            "${state.toolUnreadCount} 个工具"
        else -> "最新消息"
    }
