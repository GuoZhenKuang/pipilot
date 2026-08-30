package com.ayagmar.pimobile.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecoveryMessageTest {
    private val profile =
        HostProfile(
            id = "host-1",
            name = "Laptop",
            host = "laptop.example.ts.net",
            port = 8787,
            useTls = false,
        )

    @Test
    fun mapsDiagnosticsToActionableRecovery() {
        val network = DiagnosticsResult.NetworkError(profile, "socket refused").toRecoveryMessage()
        val auth = DiagnosticsResult.AuthError(profile, "token=super-secret").toRecoveryMessage()
        val rpc = DiagnosticsResult.RpcError(profile, "spawn failed").toRecoveryMessage()

        assertEquals("重试", network.actionLabel)
        assertEquals("更新令牌", auth.actionLabel)
        assertEquals("重新测试 Pi", rpc.actionLabel)
    }

    @Test
    fun neverRendersDiagnosticTokenValues() {
        val rendered =
            DiagnosticsResult.AuthError(profile, "invalid token super-secret-value")
                .toRecoveryMessage()
                .let { message -> "${message.title} ${message.explanation} ${message.actionLabel}" }

        assertFalse(rendered.contains("super-secret-value"))
    }
}
