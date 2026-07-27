package com.ayagmar.pimobile.hosts

import com.ayagmar.pimobile.coresessions.ShareAuthority
import com.ayagmar.pimobile.coresessions.SharedSessionLocator
import com.ayagmar.pimobile.coresessions.normalizeShareHost
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443

fun normalizeShareOrigin(raw: String): String {
    require(raw.none { character -> character.code < 32 || character.code == 127 }) { "Invalid share origin" }
    val url = raw.toHttpUrlOrNull() ?: error("Invalid share origin")
    require(url.scheme == "http" || url.scheme == "https") { "Invalid share origin scheme" }
    require(url.username.isEmpty() && url.password.isEmpty()) { "Share origin user information is not supported" }
    require(url.encodedPath == "/" && url.query == null && url.fragment == null) {
        "Share origin must not contain a path, query, or fragment"
    }
    return url.toString().removeSuffix("/")
}

fun HostProfile.endpointShareAuthority(): ShareAuthority =
    ShareAuthority(
        host = normalizeShareHost(host),
        port = port,
        tls = useTls,
    )

fun HostProfile.verifiedShareAuthority(): ShareAuthority? =
    shareOrigin?.let { origin ->
        val url = normalizeShareOrigin(origin).toHttpUrlOrNull() ?: return@let null
        ShareAuthority(
            host = normalizeShareHost(url.host),
            port = url.port.takeIf { it != 0 } ?: if (url.scheme == "https") HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT,
            tls = url.scheme == "https",
        )
    }

fun matchingProfiles(
    profiles: List<HostProfile>,
    locator: SharedSessionLocator,
): List<HostProfile> =
    profiles.filter { profile ->
        profile.endpointShareAuthority() == locator.authority ||
            profile.verifiedShareAuthority() == locator.authority
    }
