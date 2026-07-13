package com.ayagmar.pimobile.ui.chat

data class TimelineReadingState(
    val sticksToBottom: Boolean = true,
    val unreadCount: Int = 0,
)

sealed interface TimelineReadingAction {
    data object ScrollAway : TimelineReadingAction

    data object ReachBottom : TimelineReadingAction

    data object NewActivity : TimelineReadingAction

    data object DisclosureChanged : TimelineReadingAction

    data object JumpToLatest : TimelineReadingAction
}

fun reduceTimelineReadingState(
    state: TimelineReadingState,
    action: TimelineReadingAction,
): TimelineReadingState =
    when (action) {
        TimelineReadingAction.ScrollAway -> state.copy(sticksToBottom = false)
        TimelineReadingAction.NewActivity ->
            if (state.sticksToBottom) state else state.copy(unreadCount = state.unreadCount + 1)
        TimelineReadingAction.ReachBottom,
        TimelineReadingAction.JumpToLatest,
        -> TimelineReadingState()
        TimelineReadingAction.DisclosureChanged -> state
    }

fun preservedIndexAfterPrepend(
    previousIndex: Int,
    prependedCount: Int,
): Int = previousIndex + prependedCount.coerceAtLeast(0)
