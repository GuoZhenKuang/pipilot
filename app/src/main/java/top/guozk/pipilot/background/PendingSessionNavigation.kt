package top.guozk.pipilot.background

import java.util.concurrent.atomic.AtomicReference

/**
 * 通知点击直达会话的「待处理目标」桥：前台服务在通知点击时写入本地
 * SessionKey 组成部分，PipilotApp 启动/回前台时消费并导航到聊天页。
 *
 * 只承载本地内部键（hostProfileId/sessionId），与外部共享链接无关。
 */
object PendingSessionNavigation {
    data class Target(
        val hostProfileId: String,
        val sessionId: String,
    )

    private val pending = AtomicReference<Target?>(null)

    fun submit(target: Target?) {
        pending.set(target)
    }

    fun consume(): Target? = pending.getAndSet(null)
}
