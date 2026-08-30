package top.guozk.pipilot.hosts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val PAIRING_PAYLOAD_TYPE = "pi-mobile-host"
private const val PAIRING_PAYLOAD_VERSION_1 = 1
private const val PAIRING_PAYLOAD_VERSION_2 = 2

fun parseHostPairingPayload(rawValue: String): Result<HostDraft> =
    runCatching {
        val payload = Json.parseToJsonElement(rawValue).jsonObject
        require(payload.string("type") == PAIRING_PAYLOAD_TYPE) { "这不是 PiPilot 配对二维码" }
        val version = payload.int("version")
        require(version == PAIRING_PAYLOAD_VERSION_1 || version == PAIRING_PAYLOAD_VERSION_2) {
            "配对二维码版本不受支持"
        }
        val shareOrigin =
            payload.string("shareOrigin")
                ?.takeIf { origin -> origin.isNotBlank() }
                ?.let(::normalizeShareOrigin)

        val draft =
            HostDraft(
                name = payload.string("name").orEmpty(),
                host = payload.string("host").orEmpty(),
                port = payload.int("port")?.toString().orEmpty(),
                useTls = payload.boolean("useTls") ?: false,
                token = payload.string("token").orEmpty(),
                shareOrigin = shareOrigin,
            )
        require(draft.token.isNotBlank()) { "配对二维码中不包含令牌" }

        when (val validation = draft.validate()) {
            is HostValidationResult.Valid -> draft
            is HostValidationResult.Invalid -> error(validation.reason)
        }
    }

private fun Map<String, kotlinx.serialization.json.JsonElement>.string(name: String): String? =
    get(name)?.jsonPrimitive?.content

private fun Map<String, kotlinx.serialization.json.JsonElement>.int(name: String): Int? =
    get(name)?.jsonPrimitive?.intOrNull

private fun Map<String, kotlinx.serialization.json.JsonElement>.boolean(name: String): Boolean? =
    get(name)?.jsonPrimitive?.booleanOrNull
