package top.guozk.pipilot.hosts

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.guozk.pipilot.coresessions.ShareAuthority
import top.guozk.pipilot.coresessions.SharedSessionLocator
import top.guozk.pipilot.coresessions.normalizeShareHost

private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443
private const val ASCII_CONTROL_CHARACTER_LIMIT = 32
private const val ASCII_DELETE_CHARACTER = 127

fun normalizeShareOrigin(raw: String): String {
    require(
        raw.none { character ->
            character.code < ASCII_CONTROL_CHARACTER_LIMIT || character.code == ASCII_DELETE_CHARACTER
        },
    ) { "共享来源地址无效" }
    val url = raw.toHttpUrlOrNull() ?: error("共享来源地址无效")
    require(url.scheme == "http" || url.scheme == "https") { "共享来源协议无效，仅支持 http/https" }
    require(url.username.isEmpty() && url.password.isEmpty()) { "共享来源不支持携带用户信息" }
    require(url.encodedPath == "/" && url.query == null && url.fragment == null) {
        "共享来源不能包含路径、查询参数或片段"
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
