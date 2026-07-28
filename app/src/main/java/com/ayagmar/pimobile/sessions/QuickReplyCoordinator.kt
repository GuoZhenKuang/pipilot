@file:Suppress("ReturnCount", "MaxLineLength", "ktlint:standard:max-line-length")

package com.ayagmar.pimobile.sessions

import com.ayagmar.pimobile.coresessions.SessionKey
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.hosts.HostProfile
import com.ayagmar.pimobile.hosts.HostTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class QuickReplyDeliveryMode {
    FOLLOW_UP,
    STEER,
}

enum class QuickReplyPhase {
    HIDDEN,
    EDITING,
    SENDING,
    CONFLICT,
    SENT,
    ERROR,
}

data class QuickReplyState(
    val phase: QuickReplyPhase = QuickReplyPhase.HIDDEN,
    val targetKey: SessionKey? = null,
    val targetLabel: String = "",
    val draft: String = "",
    val deliveryMode: QuickReplyDeliveryMode? = null,
    val message: String? = null,
    val canOpenChat: Boolean = false,
) {
    val isVisible: Boolean
        get() = phase != QuickReplyPhase.HIDDEN
}

/**
 * Text-only dispatch owner. Every retarget/dismiss changes generation; delayed resume work must
 * re-check it before dispatching. The existing controller remains the only lock/control path.
 */
class QuickReplyCoordinator(
    private val controller: SessionController,
    private val tokenStore: HostTokenStore,
    private val hostById: (String) -> HostProfile?,
    private val recordByKey: (SessionKey) -> SessionRecord?,
    private val scope: CoroutineScope,
    private val onOpenChat: () -> Unit,
) {
    private val _state = MutableStateFlow(QuickReplyState())
    val state: StateFlow<QuickReplyState> = _state.asStateFlow()

    private var generation = 0L
    private var dispatchJob: Job? = null

    @Synchronized
    fun open(
        key: SessionKey,
        label: String,
    ) {
        generation += 1
        dispatchJob?.cancel()
        _state.value =
            QuickReplyState(
                phase = QuickReplyPhase.EDITING,
                targetKey = key,
                targetLabel = label,
            )
    }

    @Synchronized
    fun dismiss() {
        generation += 1
        dispatchJob?.cancel()
        dispatchJob = null
        _state.value = QuickReplyState()
    }

    fun updateDraft(value: String) {
        _state.value = _state.value.copy(draft = value, message = null)
    }

    fun selectDeliveryMode(mode: QuickReplyDeliveryMode) {
        if (mode == QuickReplyDeliveryMode.STEER && controller.isRetrying.value) {
            _state.value =
                _state.value.copy(
                    deliveryMode = null,
                    phase = QuickReplyPhase.ERROR,
                    message = "Steer is unavailable while Pi is retrying. Choose Follow up.",
                    canOpenChat = true,
                )
            return
        }
        _state.value = _state.value.copy(deliveryMode = mode, phase = QuickReplyPhase.EDITING, message = null)
    }

    @Synchronized
    fun send(openAfterSend: Boolean = false) {
        val current = _state.value
        if (current.phase == QuickReplyPhase.SENDING || current.phase == QuickReplyPhase.SENT) return
        val key = current.targetKey ?: return
        if (current.draft.isBlank()) return
        generation += 1
        val requestGeneration = generation
        _state.value = current.copy(phase = QuickReplyPhase.SENDING, message = null)
        dispatchJob = scope.launch { dispatch(requestGeneration, key, current.draft, openAfterSend) }
    }

    fun openCurrentChat() {
        if (_state.value.canOpenChat) onOpenChat()
    }

    @Suppress("ReturnCount")
    private suspend fun dispatch(
        requestGeneration: Long,
        key: SessionKey,
        draft: String,
        openAfterSend: Boolean,
    ) {
        val activeKey = controller.activeSession.value?.sessionKey
        val runActive = controller.isStreaming.value || controller.isRetrying.value
        if (runActive && activeKey != key) {
            publish(
                requestGeneration,
                QuickReplyPhase.CONFLICT,
                "Another session is running. Open the current session or cancel.",
                canOpenChat = activeKey != null,
            )
            return
        }

        val mode = _state.value.deliveryMode
        val result =
            if (runActive) {
                if (mode == null) {
                    publish(
                        requestGeneration,
                        QuickReplyPhase.EDITING,
                        "Choose Follow up or Steer for the active run.",
                        canOpenChat = true,
                    )
                    return
                }
                if (controller.isRetrying.value && mode == QuickReplyDeliveryMode.STEER) {
                    publish(
                        requestGeneration,
                        QuickReplyPhase.ERROR,
                        "Steer is unavailable while Pi is retrying. Choose Follow up.",
                        canOpenChat = true,
                    )
                    return
                }
                when (mode) {
                    QuickReplyDeliveryMode.FOLLOW_UP -> controller.followUp(draft)
                    QuickReplyDeliveryMode.STEER -> controller.steer(draft)
                }
            } else {
                dispatchIdleTarget(requestGeneration, key, draft) ?: return
            }

        if (!isCurrent(requestGeneration)) return
        result.fold(
            onSuccess = {
                _state.value = _state.value.copy(phase = QuickReplyPhase.SENT, message = "Reply sent", canOpenChat = true)
                if (openAfterSend) onOpenChat()
            },
            onFailure = { error ->
                publish(
                    requestGeneration,
                    QuickReplyPhase.ERROR,
                    error.message ?: "Reply could not be sent",
                    canOpenChat = controller.activeSession.value?.sessionKey != null,
                )
            },
        )
    }

    private suspend fun dispatchIdleTarget(
        requestGeneration: Long,
        key: SessionKey,
        draft: String,
    ): Result<Unit>? {
        val host = hostById(key.hostProfileId)
        if (host == null) {
            publish(requestGeneration, QuickReplyPhase.ERROR, "This host is no longer configured")
            return null
        }
        val token = tokenStore.getToken(key.hostProfileId)
        if (token.isNullOrBlank()) {
            publish(requestGeneration, QuickReplyPhase.ERROR, "Enter a token for ${host.name}")
            return null
        }
        val record = recordByKey(key)
        if (record == null || !record.hasStableIdentity || record.sessionId != key.sessionId) {
            publish(requestGeneration, QuickReplyPhase.ERROR, "Refresh sessions before replying to this target")
            return null
        }
        val resume = controller.resume(host, token, record)
        if (!isCurrent(requestGeneration)) return null
        if (resume.isFailure) {
            publish(
                requestGeneration,
                QuickReplyPhase.ERROR,
                resume.exceptionOrNull()?.message ?: "Could not acquire session control",
                canOpenChat = controller.activeSession.value?.sessionKey != null,
            )
            return null
        }
        val resolvedActiveKey = controller.activeSession.value?.sessionKey
        if (resolvedActiveKey != null && resolvedActiveKey != key) {
            publish(requestGeneration, QuickReplyPhase.ERROR, "Resumed session identity did not match the target")
            return null
        }
        if (!isCurrent(requestGeneration)) return null
        return controller.sendPrompt(draft)
    }

    @Synchronized
    private fun isCurrent(requestGeneration: Long): Boolean =
        generation == requestGeneration && _state.value.targetKey != null

    private fun publish(
        requestGeneration: Long,
        phase: QuickReplyPhase,
        message: String,
        canOpenChat: Boolean = false,
    ) {
        if (isCurrent(requestGeneration)) {
            _state.value = _state.value.copy(phase = phase, message = message, canOpenChat = canOpenChat)
        }
    }
}
