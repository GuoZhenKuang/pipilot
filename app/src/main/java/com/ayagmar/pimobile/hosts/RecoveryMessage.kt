package com.ayagmar.pimobile.hosts

data class RecoveryMessage(
    val title: String,
    val explanation: String,
    val actionLabel: String,
)

fun DiagnosticsResult.toRecoveryMessage(): RecoveryMessage {
    return when (this) {
        is DiagnosticsResult.Success ->
            RecoveryMessage(
                title = "Bridge 已就绪",
                explanation = "网络、身份认证和 Pi RPC 检查均已通过。",
                actionLabel = "打开会话",
            )
        is DiagnosticsResult.NetworkError ->
            RecoveryMessage(
                title = "无法连接 Bridge",
                explanation = "请检查 Tailscale、电脑地址、端口和 Bridge 进程。",
                actionLabel = "重试",
            )
        is DiagnosticsResult.AuthError ->
            RecoveryMessage(
                title = "认证失败",
                explanation = "请重新输入 Bridge 令牌。已保存的令牌不会显示出来。",
                actionLabel = "更新令牌",
            )
        is DiagnosticsResult.RpcError ->
            RecoveryMessage(
                title = "Pi 尚未就绪",
                explanation = "请确认已安装 Pi 0.80.6 或更高版本，并已配置模型凭据。",
                actionLabel = "重新测试 Pi",
            )
    }
}
