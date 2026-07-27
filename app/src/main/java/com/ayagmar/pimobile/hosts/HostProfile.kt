package com.ayagmar.pimobile.hosts

data class HostProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val useTls: Boolean,
    /** Authenticated/pairing-reviewed public alias. Never sourced directly from an external link. */
    val shareOrigin: String? = null,
) {
    val endpoint: String
        get() {
            val scheme = if (useTls) "wss" else "ws"
            val endpointHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
            return "$scheme://$endpointHost:$port/ws"
        }
}

data class HostProfileItem(
    val profile: HostProfile,
    val hasToken: Boolean,
    val diagnosticStatus: DiagnosticStatus = DiagnosticStatus.NONE,
)

enum class DiagnosticStatus {
    NONE,
    TESTING,
    SUCCESS,
    FAILED,
}

data class HostDraft(
    val id: String? = null,
    val name: String = "",
    val host: String = "",
    val port: String = DEFAULT_PORT,
    val useTls: Boolean = false,
    val token: String = "",
    val shareOrigin: String? = null,
) {
    fun validate(): HostValidationResult {
        val parsedPort = port.toIntOrNull()
        val validationError =
            when {
                name.isBlank() -> "Name is required"
                host.isBlank() -> "Host is required"
                parsedPort == null -> "Port must be between $MIN_PORT and $MAX_PORT"
                parsedPort !in MIN_PORT..MAX_PORT -> "Port must be between $MIN_PORT and $MAX_PORT"
                else -> null
            }

        if (validationError != null) {
            return HostValidationResult.Invalid(validationError)
        }

        return HostValidationResult.Valid(
            profile =
                HostProfile(
                    id = id ?: "",
                    name = name.trim(),
                    host = host.trim(),
                    port = requireNotNull(parsedPort),
                    useTls = useTls,
                    shareOrigin = shareOrigin,
                ),
        )
    }

    companion object {
        const val DEFAULT_PORT = "8787"
        const val MIN_PORT = 1
        const val MAX_PORT = 65535
    }
}

sealed interface HostValidationResult {
    data class Valid(
        val profile: HostProfile,
    ) : HostValidationResult

    data class Invalid(
        val reason: String,
    ) : HostValidationResult
}
