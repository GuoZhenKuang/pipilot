package com.ayagmar.pimobile.hosts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val PAIRING_PAYLOAD_TYPE = "pi-mobile-host"
private const val PAIRING_PAYLOAD_VERSION = 1

fun parseHostPairingPayload(rawValue: String): Result<HostDraft> =
    runCatching {
        val payload = Json.parseToJsonElement(rawValue).jsonObject
        require(payload.string("type") == PAIRING_PAYLOAD_TYPE) { "This QR code is not for Pi Mobile" }
        require(payload.int("version") == PAIRING_PAYLOAD_VERSION) { "This pairing QR version is not supported" }

        val draft =
            HostDraft(
                name = payload.string("name").orEmpty(),
                host = payload.string("host").orEmpty(),
                port = payload.int("port")?.toString().orEmpty(),
                useTls = payload.boolean("useTls") ?: false,
                token = payload.string("token").orEmpty(),
            )
        require(draft.token.isNotBlank()) { "The pairing QR does not contain a token" }

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
