package top.guozk.pipilot.corenet

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SocketTransport {
    val inboundMessages: Flow<String>

    /** 入站流当前订阅数；用于在消费方就绪后再建立连接，避免握手消息早于订阅而丢失。 */
    val inboundSubscriptionCount: StateFlow<Int>
    val connectionState: StateFlow<ConnectionState>

    suspend fun connect(target: WebSocketTarget)

    suspend fun reconnect()

    suspend fun disconnect()

    suspend fun send(message: String)
}
