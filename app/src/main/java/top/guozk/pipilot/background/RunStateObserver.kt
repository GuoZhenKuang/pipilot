package top.guozk.pipilot.background

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.guozk.pipilot.corerpc.RpcIncomingMessage
import top.guozk.pipilot.sessions.SessionController

/**
 * 应用级运行状态观察器：把控制器暴露的 isStreaming/activeSession 归并为
 * 一个隐私安全的状态快照，供前台服务与通知消费。
 *
 * 红线：快照只包含运行阶段与主机/会话的内部键，绝不包含提示词、助手文本、
 * cwd、路径、令牌或共享引用。
 */
class RunStateObserver(
    private val sessionController: SessionController,
) {
    enum class Phase { IDLE, STREAMING, RETRYING }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val connected: Boolean = false,
        /** 通知点击恢复所需的最小定位信息（本地内部键，非外部链接）。 */
        val hostProfileId: String? = null,
        val sessionId: String? = null,
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private var job: Job? = null

    /** 最近一次运行是否以失败告终（assistantMessageEvent error/stopReason）。 */
    @Volatile
    var lastRunFailed: Boolean = false
        private set

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job =
            scope.launch {
                launch {
                    sessionController.activeSession.collect { active ->
                        update {
                            val key = active?.sessionKey
                            it.copy(hostProfileId = key?.hostProfileId, sessionId = key?.sessionId)
                        }
                    }
                }
                launch {
                    sessionController.connectionState.collect { state ->
                        update { it.copy(connected = state == top.guozk.pipilot.corenet.ConnectionState.CONNECTED) }
                    }
                }
                launch {
                    sessionController.rpcEvents.collect { event ->
                        onRpcEvent(event)
                    }
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    internal fun onRpcEvent(event: RpcIncomingMessage) {
        when (event) {
            is top.guozk.pipilot.corerpc.AgentStartEvent -> {
                lastRunFailed = false
                update { it.copy(phase = Phase.STREAMING) }
            }
            is top.guozk.pipilot.corerpc.AgentSettledEvent ->
                update {
                    if (it.phase != Phase.IDLE) it.copy(phase = Phase.IDLE) else it
                }
            is top.guozk.pipilot.corerpc.MessageUpdateEvent ->
                if (event.assistantMessageEvent?.type == "error") {
                    lastRunFailed = true
                }
            else -> Unit
        }
    }

    private fun update(transform: (Snapshot) -> Snapshot) {
        val next = transform(_snapshot.value)
        if (next != _snapshot.value) {
            _snapshot.value = next
        }
    }
}
