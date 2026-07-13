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
                title = "Bridge ready",
                explanation = "Network, authentication, and Pi RPC checks passed.",
                actionLabel = "Open sessions",
            )
        is DiagnosticsResult.NetworkError ->
            RecoveryMessage(
                title = "Cannot reach the bridge",
                explanation = "Check Tailscale, the computer address, port, and bridge process.",
                actionLabel = "Try again",
            )
        is DiagnosticsResult.AuthError ->
            RecoveryMessage(
                title = "Authentication rejected",
                explanation = "Enter the bridge token again. Stored tokens are never displayed.",
                actionLabel = "Update token",
            )
        is DiagnosticsResult.RpcError ->
            RecoveryMessage(
                title = "Pi is not ready",
                explanation = "Verify Pi 0.80.6 or newer is installed and its model credentials are configured.",
                actionLabel = "Test Pi again",
            )
    }
}
