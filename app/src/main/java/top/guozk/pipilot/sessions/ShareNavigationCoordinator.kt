package top.guozk.pipilot.sessions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.guozk.pipilot.coresessions.SessionKey
import top.guozk.pipilot.coresessions.SharedSessionLocator
import top.guozk.pipilot.coresessions.SharedSessionLocatorCodec
import top.guozk.pipilot.hosts.HostProfileStore
import top.guozk.pipilot.hosts.HostTokenStore
import top.guozk.pipilot.hosts.matchingProfiles

sealed interface ShareNavigationState {
    data object Idle : ShareNavigationState

    data object Resolving : ShareNavigationState

    data object SetupRequired : ShareNavigationState

    data object AuthenticationRequired : ShareNavigationState

    data object AmbiguousHost : ShareNavigationState

    data class Failed(
        val kind: ShareNavigationFailure,
        val message: String,
    ) : ShareNavigationState

    data class NavigateToChat(
        val generation: Long,
        val sessionKey: SessionKey,
    ) : ShareNavigationState
}

enum class ShareNavigationFailure {
    INVALID_LINK,
    AUTHENTICATION,
    UNREACHABLE,
    UNAVAILABLE,
    SHARE_STATE,
    UNSUPPORTED_VERSION,
    LOCK_CONFLICT,
    RESUME,
}

/**
 * Application-scoped delivery owner. A generation is resolved once, newer links cancel older work,
 * and successful navigation remains replayable until the UI acknowledges it.
 */
class ShareNavigationCoordinator(
    private val profileStore: HostProfileStore,
    private val tokenStore: HostTokenStore,
    private val shareSource: BridgeSessionShareRemoteDataSource,
    private val sessionController: SessionController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow<ShareNavigationState>(ShareNavigationState.Idle)
    val state: StateFlow<ShareNavigationState> = _state.asStateFlow()

    private var generation = 0L
    private var activeJob: Job? = null
    private var lastLocator: SharedSessionLocator? = null

    @Synchronized
    fun submitExternalIntent(
        action: String?,
        data: String?,
    ) {
        if (action != ACTION_VIEW || data == null) return
        val locator =
            SharedSessionLocatorCodec.decode(data).getOrElse { error ->
                generation += 1
                activeJob?.cancel()
                _state.value =
                    ShareNavigationState.Failed(
                        kind =
                            if (error.message?.contains("version", ignoreCase = true) == true) {
                                ShareNavigationFailure.UNSUPPORTED_VERSION
                            } else {
                                ShareNavigationFailure.INVALID_LINK
                            },
                        message = "该 PiPilot 链接无效或不受支持",
                    )
                return
            }
        submit(locator)
    }

    @Synchronized
    fun retry() {
        lastLocator?.let(::submit)
    }

    @Synchronized
    fun cancel() {
        generation += 1
        activeJob?.cancel()
        activeJob = null
        _state.value = ShareNavigationState.Idle
    }

    @Synchronized
    fun acknowledgeNavigation(expectedGeneration: Long) {
        val current = _state.value
        if (current is ShareNavigationState.NavigateToChat && current.generation == expectedGeneration) {
            _state.value = ShareNavigationState.Idle
        }
    }

    @Synchronized
    private fun submit(locator: SharedSessionLocator) {
        generation += 1
        val requestGeneration = generation
        activeJob?.cancel()
        lastLocator = locator
        _state.value = ShareNavigationState.Resolving
        activeJob =
            scope.launch {
                resolve(requestGeneration, locator)
            }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private suspend fun resolve(
        requestGeneration: Long,
        locator: SharedSessionLocator,
    ) {
        val matches = matchingProfiles(profileStore.list(), locator)
        if (matches.isEmpty()) {
            publish(requestGeneration, ShareNavigationState.SetupRequired)
            return
        }
        if (matches.size != 1) {
            publish(requestGeneration, ShareNavigationState.AmbiguousHost)
            return
        }
        val profile = matches.single()
        val token = tokenStore.getToken(profile.id)
        if (token.isNullOrBlank()) {
            publish(requestGeneration, ShareNavigationState.AuthenticationRequired)
            return
        }

        try {
            val session = shareSource.resolve(profile.id, locator.shareReference)
            if (!session.hasStableIdentity) {
                publishFailure(
                    requestGeneration,
                    ShareNavigationFailure.UNAVAILABLE,
                    "该共享会话不可用或已被撤销",
                )
                return
            }
            val sessionId = requireNotNull(session.sessionId)
            val resume = sessionController.resume(profile, token, session)
            if (resume.isFailure) {
                val message = resume.exceptionOrNull()?.message.orEmpty()
                val isLockConflict = message.contains("lock", ignoreCase = true) || message.contains("control", true)
                publishFailure(
                    requestGeneration,
                    if (isLockConflict) ShareNavigationFailure.LOCK_CONFLICT else ShareNavigationFailure.RESUME,
                    if (isLockConflict) {
                        "该会话正被另一个客户端控制，请重试或打开会话页。"
                    } else {
                        "无法恢复该共享会话"
                    },
                )
                return
            }
            publish(
                requestGeneration,
                ShareNavigationState.NavigateToChat(
                    generation = requestGeneration,
                    sessionKey = SessionKey(profile.id, sessionId),
                ),
            )
        } catch (error: BridgeShareException) {
            val failure =
                when (error.code) {
                    "missing_token" -> ShareNavigationFailure.AUTHENTICATION
                    "share_not_found" -> ShareNavigationFailure.UNAVAILABLE
                    "share_state_unavailable" -> ShareNavigationFailure.SHARE_STATE
                    "control_lock_denied", "control_lock_required" -> ShareNavigationFailure.LOCK_CONFLICT
                    else -> ShareNavigationFailure.UNREACHABLE
                }
            publishFailure(requestGeneration, failure, error.message ?: "无法打开该共享会话")
        } catch (_: Throwable) {
            publishFailure(
                requestGeneration,
                ShareNavigationFailure.UNREACHABLE,
                "无法连接已配置的 Bridge",
            )
        }
    }

    @Synchronized
    private fun publish(
        requestGeneration: Long,
        next: ShareNavigationState,
    ) {
        if (generation == requestGeneration) _state.value = next
    }

    private fun publishFailure(
        requestGeneration: Long,
        failure: ShareNavigationFailure,
        message: String,
    ) {
        publish(requestGeneration, ShareNavigationState.Failed(failure, message))
    }

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
    }
}
