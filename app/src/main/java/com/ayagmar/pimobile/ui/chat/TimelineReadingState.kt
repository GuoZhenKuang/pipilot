package com.ayagmar.pimobile.ui.chat

data class TimelineReadingState(
    val sticksToBottom: Boolean = true,
    val unreadCount: Int = 0,
)

sealed interface TimelineReadingAction {
    data object ScrollAway : TimelineReadingAction

    data object ReachBottom : TimelineReadingAction

    data class NewActivity(val count: Int = 1) : TimelineReadingAction

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
            if (state.sticksToBottom || action.count <= 0) {
                state
            } else {
                state.copy(unreadCount = state.unreadCount + action.count)
            }
        TimelineReadingAction.ReachBottom,
        TimelineReadingAction.JumpToLatest,
        -> TimelineReadingState()
        TimelineReadingAction.DisclosureChanged -> state.copy(sticksToBottom = false)
    }

fun timelineActivityIdentities(timeline: List<com.ayagmar.pimobile.chat.ChatTimelineItem>): List<String> =
    timeline.mapNotNull { item ->
        when (item) {
            is com.ayagmar.pimobile.chat.ChatTimelineItem.Assistant ->
                if (item.text.isNotBlank() || !item.thinking.isNullOrBlank() || !item.isStreaming) {
                    "assistant:${item.id}"
                } else {
                    null
                }
            is com.ayagmar.pimobile.chat.ChatTimelineItem.Tool -> "tool:${item.id}"
            is com.ayagmar.pimobile.chat.ChatTimelineItem.User -> "user:${item.id}"
        }
    }

fun countNewTimelineActivities(
    previous: List<String>,
    current: List<String>,
): Int {
    if (previous.isEmpty()) return 0
    val previousIdentities = previous.toHashSet()
    val previousTailIndex = current.indexOfLast { it == previous.last() }
    val appended = if (previousTailIndex >= 0) current.drop(previousTailIndex + 1) else current
    return appended.count { it !in previousIdentities }
}
