package com.ayagmar.pimobile.sessions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun parseForkableMessages(data: JsonObject?): List<ForkableMessage> {
    val messages = runCatching { data?.get("messages")?.jsonArray }.getOrNull() ?: JsonArray(emptyList())

    return messages.mapNotNull { messageElement ->
        val messageObject = messageElement.jsonObject
        val entryId = messageObject.stringField("entryId") ?: return@mapNotNull null
        // pi RPC currently returns "text" for fork messages; keep "preview" as fallback.
        val preview = messageObject.stringField("text") ?: messageObject.stringField("preview") ?: "(no preview)"
        val timestamp = messageObject["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

        ForkableMessage(
            entryId = entryId,
            preview = preview,
            timestamp = timestamp,
        )
    }
}

internal fun parseSessionTreeSnapshot(payload: JsonObject): SessionTreeSnapshot {
    val sessionPath = payload.stringField("sessionPath") ?: error("Session tree response missing sessionPath")
    val rootIds =
        runCatching {
            payload["rootIds"]?.jsonArray?.mapNotNull { element ->
                element.jsonPrimitive.contentOrNull
            }
        }.getOrNull() ?: emptyList()

    val entries =
        runCatching {
            payload["entries"]?.jsonArray?.mapNotNull { element ->
                val entryObject = element.jsonObject
                val entryId = entryObject.stringField("entryId") ?: return@mapNotNull null
                SessionTreeEntry(
                    entryId = entryId,
                    parentId = entryObject.stringField("parentId"),
                    entryType = entryObject.stringField("entryType") ?: "entry",
                    role = entryObject.stringField("role"),
                    timestamp = entryObject.stringField("timestamp"),
                    preview = entryObject.stringField("preview") ?: "entry",
                    label = entryObject.stringField("label"),
                    isBookmarked = entryObject.booleanField("isBookmarked") ?: false,
                )
            }
        }.getOrNull() ?: emptyList()

    return SessionTreeSnapshot(
        sessionPath = sessionPath,
        rootIds = rootIds,
        currentLeafId = payload.stringField("currentLeafId"),
        entries = entries,
    )
}

internal fun parseSessionFreshnessSnapshot(payload: JsonObject): SessionFreshnessSnapshot {
    val sessionPath = payload.stringField("sessionPath") ?: error("Session freshness response missing sessionPath")
    val cwd = payload.stringField("cwd") ?: error("Session freshness response missing cwd")

    val fingerprintPayload = runCatching { payload["fingerprint"]?.jsonObject }.getOrNull()
    val lockPayload = runCatching { payload["lock"]?.jsonObject }.getOrNull()

    val fingerprint =
        SessionFreshnessFingerprint(
            mtimeMs = fingerprintPayload.longField("mtimeMs") ?: 0L,
            sizeBytes = fingerprintPayload.longField("sizeBytes") ?: 0L,
            entryCount = fingerprintPayload.intField("entryCount") ?: 0,
            lastEntryId = fingerprintPayload.stringField("lastEntryId"),
            lastEntriesHash = fingerprintPayload.stringField("lastEntriesHash"),
        )

    val lock =
        SessionLockMetadata(
            cwdOwnerClientId = lockPayload.stringField("cwdOwnerClientId"),
            sessionOwnerClientId = lockPayload.stringField("sessionOwnerClientId"),
            isCurrentClientCwdOwner = lockPayload.booleanField("isCurrentClientCwdOwner") ?: false,
            isCurrentClientSessionOwner = lockPayload.booleanField("isCurrentClientSessionOwner") ?: false,
        )

    return SessionFreshnessSnapshot(
        sessionPath = sessionPath,
        cwd = cwd,
        fingerprint = fingerprint,
        lock = lock,
    )
}

internal fun parseTreeNavigationResult(payload: JsonObject): TreeNavigationResult {
    return TreeNavigationResult(
        cancelled = payload.booleanField("cancelled") ?: false,
        editorText = payload.stringField("editorText"),
        currentLeafId = payload.stringField("currentLeafId"),
        sessionPath = payload.stringField("sessionPath"),
    )
}

private fun JsonObject?.stringField(fieldName: String): String? {
    val jsonObject = this ?: return null
    return jsonObject[fieldName]?.jsonPrimitive?.contentOrNull
}

private fun JsonObject?.booleanField(fieldName: String): Boolean? {
    val value = this?.get(fieldName)?.jsonPrimitive?.contentOrNull ?: return null
    return value.toBooleanStrictOrNull()
}

internal fun parseModelInfo(data: JsonObject?): ModelInfo? {
    val nestedModel = data?.get("model") as? JsonObject
    val model = nestedModel ?: data?.takeIf { it.stringField("id") != null } ?: return null

    return ModelInfo(
        id = model.stringField("id") ?: "unknown",
        name = model.stringField("name") ?: "Unknown Model",
        provider = model.stringField("provider") ?: "unknown",
        thinkingLevel = data.stringField("thinkingLevel") ?: "off",
        contextWindow = model.intField("contextWindow"),
    )
}

internal fun parseSlashCommands(data: JsonObject?): List<SlashCommandInfo> {
    val commands = runCatching { data?.get("commands")?.jsonArray }.getOrNull() ?: JsonArray(emptyList())

    return commands.mapNotNull { commandElement ->
        val commandObject = commandElement.jsonObject
        val name = commandObject.stringField("name") ?: return@mapNotNull null
        SlashCommandInfo(
            name = name,
            description = commandObject.stringField("description"),
            source = commandObject.stringField("source") ?: "unknown",
            location = commandObject.stringField("location"),
            path = commandObject.stringField("path"),
        )
    }
}

private fun JsonObject?.longField(fieldName: String): Long? {
    val value = this?.get(fieldName)?.jsonPrimitive?.contentOrNull ?: return null
    return value.toLongOrNull()
}

private fun JsonObject?.intField(fieldName: String): Int? {
    val value = this?.get(fieldName)?.jsonPrimitive?.contentOrNull ?: return null
    return value.toIntOrNull()
}
