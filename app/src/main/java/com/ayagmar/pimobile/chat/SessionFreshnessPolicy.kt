package com.ayagmar.pimobile.chat

data class SessionFreshnessPolicyInput(
    val fingerprintChanged: Boolean,
    val currentClientOwnsLock: Boolean,
    val differentClientOwnsLock: Boolean,
    val chatIsBusy: Boolean,
    val insideLocalMutationGraceWindow: Boolean,
)

enum class SessionFreshnessAction {
    UPDATE_BASELINE,
    SHOW_CONFLICT,
    REFRESH_SILENTLY,
    DEFER_REFRESH,
}

fun classifySessionFreshness(input: SessionFreshnessPolicyInput): SessionFreshnessAction =
    when {
        !input.fingerprintChanged || input.insideLocalMutationGraceWindow ->
            SessionFreshnessAction.UPDATE_BASELINE
        input.differentClientOwnsLock && !input.currentClientOwnsLock ->
            SessionFreshnessAction.SHOW_CONFLICT
        input.chatIsBusy -> SessionFreshnessAction.DEFER_REFRESH
        else -> SessionFreshnessAction.REFRESH_SILENTLY
    }
