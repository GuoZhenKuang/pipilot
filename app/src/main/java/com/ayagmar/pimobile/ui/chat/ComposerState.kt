package com.ayagmar.pimobile.ui.chat

enum class ActiveRunDeliveryMode {
    FOLLOW_UP,
    STEER,
}

data class ComposerState(
    val draft: String = "",
    val deliveryMode: ActiveRunDeliveryMode = ActiveRunDeliveryMode.FOLLOW_UP,
)

sealed interface ComposerAction {
    data class ChangeDraft(val value: String) : ComposerAction

    data class SelectMode(val mode: ActiveRunDeliveryMode) : ComposerAction

    data object Submit : ComposerAction
}

fun reduceComposerState(
    state: ComposerState,
    action: ComposerAction,
): ComposerState =
    when (action) {
        is ComposerAction.ChangeDraft -> state.copy(draft = action.value)
        is ComposerAction.SelectMode -> state.copy(deliveryMode = action.mode)
        ComposerAction.Submit -> if (state.draft.isBlank()) state else state.copy(draft = "")
    }
