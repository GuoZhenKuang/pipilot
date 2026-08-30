package top.guozk.pipilot.hosts

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.guozk.pipilot.corenet.ConnectionState
import top.guozk.pipilot.corenet.PiRpcConnection
import top.guozk.pipilot.corenet.PiRpcConnectionConfig
import top.guozk.pipilot.corenet.WebSocketTarget
import top.guozk.pipilot.corerpc.RpcResponse

/**
 * Result of connection diagnostics check.
 */
sealed interface DiagnosticsResult {
    val hostProfile: HostProfile

    data class Success(
        override val hostProfile: HostProfile,
        val bridgeVersion: String?,
        val model: String?,
        val cwd: String?,
    ) : DiagnosticsResult

    data class NetworkError(
        override val hostProfile: HostProfile,
        val message: String,
    ) : DiagnosticsResult

    data class AuthError(
        override val hostProfile: HostProfile,
        val message: String,
    ) : DiagnosticsResult

    data class RpcError(
        override val hostProfile: HostProfile,
        val message: String,
    ) : DiagnosticsResult
}

/**
 * Performs connection diagnostics to verify bridge connectivity and auth.
 */
class ConnectionDiagnostics {
    @Suppress("TooGenericExceptionCaught")
    suspend fun testHost(
        hostProfile: HostProfile,
        token: String,
        timeoutMs: Long = 10_000,
    ): DiagnosticsResult {
        val connection = PiRpcConnection()

        return try {
            val response = connectAndRequestState(connection, hostProfile, token, timeoutMs)
            response.toDiagnosticsResult(hostProfile)
        } catch (error: TimeoutCancellationException) {
            DiagnosticsResult.NetworkError(
                hostProfile = hostProfile,
                message = "连接在 $timeoutMs 毫秒后超时（${error::class.simpleName}）",
            )
        } catch (error: Exception) {
            mapError(hostProfile, error)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun connectAndRequestState(
        connection: PiRpcConnection,
        hostProfile: HostProfile,
        token: String,
        timeoutMs: Long,
    ) = withTimeout(timeoutMs) {
        connection.connect(createConnectionConfig(hostProfile, token))
        connection.connectionState.first { state -> state == ConnectionState.CONNECTED }
        connection.requestState()
    }

    private fun RpcResponse.toDiagnosticsResult(hostProfile: HostProfile): DiagnosticsResult {
        if (!success) {
            return DiagnosticsResult.RpcError(
                hostProfile = hostProfile,
                message = error ?: "未知 RPC 错误",
            )
        }

        return DiagnosticsResult.Success(
            hostProfile = hostProfile,
            bridgeVersion = null,
            model = data?.extractModelName(),
            cwd = data?.stringField("cwd"),
        )
    }

    private fun mapError(
        hostProfile: HostProfile,
        error: Exception,
    ): DiagnosticsResult {
        val message = error.message.orEmpty()

        return when {
            message.contains("401", ignoreCase = true) ||
                message.contains("unauthorized", ignoreCase = true) -> {
                DiagnosticsResult.AuthError(
                    hostProfile = hostProfile,
                    message = "认证失败：令牌无效",
                )
            }

            message.contains("refused", ignoreCase = true) ||
                message.contains("unreachable", ignoreCase = true) -> {
                DiagnosticsResult.NetworkError(
                    hostProfile = hostProfile,
                    message = "无法连接 Bridge：$message",
                )
            }

            else -> {
                DiagnosticsResult.NetworkError(
                    hostProfile = hostProfile,
                    message = if (message.isBlank()) "未知错误" else message,
                )
            }
        }
    }

    private fun createConnectionConfig(
        hostProfile: HostProfile,
        token: String,
    ): PiRpcConnectionConfig {
        val target =
            WebSocketTarget(
                url = hostProfile.endpoint,
                headers = mapOf("Authorization" to "Bearer $token"),
            )

        return PiRpcConnectionConfig(
            target = target,
            cwd = "/tmp",
            sessionPath = null,
        )
    }
}

private fun JsonObject.stringField(fieldName: String): String? {
    return this[fieldName]?.jsonPrimitive?.contentOrNull
}

private fun JsonObject.extractModelName(): String? {
    val modelElement = this["model"] ?: return null
    return modelElement.extractModelName()
}

private fun JsonElement.extractModelName(): String? {
    return when (this) {
        is JsonObject -> stringField("name") ?: stringField("id")
        else -> jsonPrimitive.contentOrNull
    }
}
