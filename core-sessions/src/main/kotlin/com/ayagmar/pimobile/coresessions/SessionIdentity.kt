package com.ayagmar.pimobile.coresessions

import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val SHARE_REFERENCE_REGEX = Regex("^[A-Za-z0-9_-]{22}$")
private val CONTROL_CHARACTER_REGEX = Regex("[\\u0000-\\u001f\\u007f]")

/** Authenticated, device-local identity. It must never be encoded into an external link. */
data class SessionKey(
    val hostProfileId: String,
    val sessionId: String,
) {
    init {
        require(hostProfileId.isNotBlank()) { "Host profile is required" }
        require(sessionId.isValidPiSessionId()) { "Invalid session identity" }
    }
}

/** Non-secret authority carried by an external locator. */
data class ShareAuthority(
    val host: String,
    val port: Int,
    val tls: Boolean,
) {
    init {
        require(host == normalizeShareHost(host)) { "Authority host is not canonical" }
        require(port in 1..65_535) { "Authority port is invalid" }
    }
}

/** External identity. Deliberately cannot contain a Pi ID, profile ID, path, cwd, or token. */
data class SharedSessionLocator(
    val authority: ShareAuthority,
    val shareReference: String,
    val version: Int = CURRENT_SHARED_SESSION_LOCATOR_VERSION,
) {
    init {
        require(version == CURRENT_SHARED_SESSION_LOCATOR_VERSION) { "Unsupported link version" }
        require(SHARE_REFERENCE_REGEX.matches(shareReference)) { "Invalid share reference" }
    }
}

object SharedSessionLocatorCodec {
    fun encode(locator: SharedSessionLocator): String {
        val encodedHost = URLEncoder.encode(locator.authority.host, StandardCharsets.UTF_8).replace("+", "%20")
        return "pimobile://open/v${locator.version}/${locator.shareReference}" +
            "?host=$encodedHost&port=${locator.authority.port}&tls=${if (locator.authority.tls) 1 else 0}"
    }

    fun decode(raw: String): Result<SharedSessionLocator> =
        runCatching {
            require(raw.length <= MAX_EXTERNAL_URI_LENGTH) { "Link is too long" }
            require(!CONTROL_CHARACTER_REGEX.containsMatchIn(raw)) { "Link contains invalid characters" }
            require(!raw.contains('#')) { "Link fragments are not supported" }
            require(!raw.contains('+')) { "Ambiguous parameter encoding" }

            val uri = URI(raw)
            require(uri.scheme == "pimobile") { "Unsupported link scheme" }
            require(uri.rawAuthority == "open") { "Unsupported link authority" }
            require(uri.userInfo == null) { "User information is not supported" }
            require(uri.rawPath == "/v1/${uri.rawPath.substringAfterLast('/')}") { "Unsupported link path" }

            val segments = uri.rawPath.split('/').filter(String::isNotEmpty)
            require(segments.size == 2 && segments[0] == "v1") { "Unsupported link version" }
            val reference = segments[1]
            require(SHARE_REFERENCE_REGEX.matches(reference)) { "Invalid share reference" }

            val parameters = parseStrictQuery(uri.rawQuery)
            require(parameters.keys == setOf("host", "port", "tls")) { "Unsupported link parameters" }
            val host = normalizeShareHost(requireNotNull(parameters["host"]))
            val port = requireNotNull(parameters["port"]).toIntOrNull()
            require(port != null && port in 1..65_535) { "Invalid link port" }
            val tls =
                when (parameters["tls"]) {
                    "0" -> false
                    "1" -> true
                    else -> error("Invalid TLS parameter")
                }

            SharedSessionLocator(
                authority = ShareAuthority(host = host, port = port, tls = tls),
                shareReference = reference,
            )
        }

    fun redact(raw: String): String =
        if (decode(raw).isSuccess) "pimobile://open/v1/<redacted>" else "<invalid Pi Mobile link>"

    private fun parseStrictQuery(rawQuery: String?): Map<String, String> {
        require(!rawQuery.isNullOrBlank()) { "Missing link parameters" }
        val values = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { component ->
            val parts = component.split('=', limit = 2)
            require(parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) { "Malformed link parameter" }
            val name = decodeParameter(parts[0])
            val value = decodeParameter(parts[1])
            require(values.put(name, value) == null) { "Duplicate link parameter" }
        }
        return values
    }

    private fun decodeParameter(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)
}

fun String?.isValidPiSessionId(): Boolean {
    if (this == null || length !in 1..128) return false
    return all { character -> character.code in 0x21..0x7e }
}

fun normalizeShareHost(raw: String): String {
    require(raw.isNotBlank() && raw.length <= 253) { "Invalid authority host" }
    require(!CONTROL_CHARACTER_REGEX.containsMatchIn(raw)) { "Invalid authority host" }
    require(!raw.contains('@') && !raw.contains('/') && !raw.contains('\\')) { "Invalid authority host" }

    val withoutBrackets =
        if (raw.startsWith('[') && raw.endsWith(']')) raw.substring(1, raw.length - 1) else raw
    require(withoutBrackets.isNotBlank()) { "Invalid authority host" }

    return if (withoutBrackets.contains(':')) {
        require(withoutBrackets.matches(Regex("^[0-9A-Fa-f:.%]+$"))) { "Invalid IPv6 authority" }
        withoutBrackets.lowercase()
    } else {
        IDN.toASCII(withoutBrackets.removeSuffix("."), IDN.USE_STD3_ASCII_RULES).lowercase()
    }
}

const val CURRENT_SHARED_SESSION_LOCATOR_VERSION = 1
private const val MAX_EXTERNAL_URI_LENGTH = 2_048
