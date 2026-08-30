package top.guozk.pipilot.sessions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class SessionEntryProjection {
    private val entriesById = linkedMapOf<String, JsonObject>()
    private var leafId: String? = null

    fun reset() {
        entriesById.clear()
        leafId = null
    }

    fun apply(
        data: JsonObject?,
        fullRebuild: Boolean,
    ): ProjectionUpdate {
        val incoming = parseEntries(data)
        val nextLeafId = data.stringField("leafId")
        if (incoming == null) {
            return ProjectionUpdate.RebuildRequired
        }

        if (fullRebuild) entriesById.clear()
        incoming.forEach { entry -> entriesById[entry.getValue("id").jsonPrimitive.content] = entry }
        val branchContinues = fullRebuild || leafId == null || isAncestor(leafId, nextLeafId)

        val update =
            if (branchContinues && hasValidPath(nextLeafId)) {
                leafId = nextLeafId
                ProjectionUpdate.Applied(buildMessages())
            } else {
                ProjectionUpdate.RebuildRequired
            }
        return update
    }

    private fun parseEntries(data: JsonObject?): List<JsonObject>? {
        val incoming = runCatching { data?.get("entries")?.jsonArray }.getOrNull()
        return incoming?.mapNotNull { element ->
            val entry = runCatching { element.jsonObject }.getOrNull()
            val type = entry.stringField("type")
            val id = entry.stringField("id")
            if (entry != null && id != null && type in SUPPORTED_ENTRY_TYPES) entry else null
        }?.takeIf { entries -> entries.size == incoming.size }
    }

    private fun isAncestor(
        ancestorId: String?,
        descendantId: String?,
    ): Boolean {
        var current = descendantId
        val visited = mutableSetOf<String>()
        var found = ancestorId == null
        while (!found && current != null && visited.add(current)) {
            found = current == ancestorId
            current = entriesById[current].stringField("parentId")
        }
        return found
    }

    private fun hasValidPath(candidateLeafId: String?): Boolean {
        var current = candidateLeafId
        val visited = mutableSetOf<String>()
        var valid = candidateLeafId != null || entriesById.isEmpty()
        while (current != null && visited.add(current)) {
            val entry = entriesById[current]
            valid = entry != null
            current = entry.stringField("parentId")
        }
        return valid && current == null
    }

    private fun buildMessages(): JsonArray {
        val path = activePath()
        val compactionIndex = path.indexOfLast { entry -> entry.stringField("type") == "compaction" }
        val contextEntries =
            if (compactionIndex < 0) {
                path
            } else {
                val compaction = path[compactionIndex]
                val firstKeptId = compaction.stringField("firstKeptEntryId")
                val firstKeptIndex = path.indexOfFirst { entry -> entry.stringField("id") == firstKeptId }
                buildList {
                    add(compaction)
                    if (firstKeptIndex >= 0) addAll(path.subList(firstKeptIndex, compactionIndex))
                    addAll(path.drop(compactionIndex + 1))
                }
            }

        return buildJsonArray {
            contextEntries.forEach { entry ->
                entry.toAgentMessage()?.let(::add)
            }
        }
    }

    private fun activePath(): List<JsonObject> {
        val reversed = mutableListOf<JsonObject>()
        var current = leafId
        while (current != null) {
            val entry = entriesById.getValue(current)
            reversed += entry
            current = entry.stringField("parentId")
        }
        return reversed.asReversed()
    }

    private fun JsonObject.toAgentMessage(): JsonObject? {
        return when (stringField("type")) {
            "message" -> runCatching { get("message")?.jsonObject }.getOrNull()
            "compaction" ->
                buildJsonObject {
                    put("role", "compactionSummary")
                    put("summary", stringField("summary").orEmpty())
                    put("tokensBefore", longField("tokensBefore") ?: 0L)
                    put("timestamp", timestampMillis())
                }
            "branch_summary" ->
                buildJsonObject {
                    put("role", "branchSummary")
                    put("summary", stringField("summary").orEmpty())
                    put("fromId", stringField("fromId").orEmpty())
                    put("timestamp", timestampMillis())
                }
            "custom_message" ->
                buildJsonObject {
                    put("role", "custom")
                    put("customType", stringField("customType").orEmpty())
                    this@toAgentMessage["content"]?.let { content -> put("content", content) }
                    put("display", booleanField("display") ?: false)
                    put("timestamp", timestampMillis())
                }
            else -> null
        }
    }

    private fun JsonObject.timestampMillis(): Long {
        val timestamp = stringField("timestamp") ?: return 0L
        return runCatching { java.time.Instant.parse(timestamp).toEpochMilli() }.getOrDefault(0L)
    }

    private companion object {
        val SUPPORTED_ENTRY_TYPES =
            setOf(
                "message",
                "compaction",
                "branch_summary",
                "custom_message",
                "custom",
                "model_change",
                "thinking_level_change",
                "label",
                "session_info",
            )
    }
}

internal sealed interface ProjectionUpdate {
    data class Applied(val messages: JsonArray) : ProjectionUpdate

    data object RebuildRequired : ProjectionUpdate
}

private fun JsonObject?.stringField(name: String): String? {
    return runCatching { this?.get(name)?.jsonPrimitive?.contentOrNull }.getOrNull()
}

private fun JsonObject.longField(name: String): Long? {
    return runCatching { get(name)?.jsonPrimitive?.longOrNull }.getOrNull()
}

private fun JsonObject.booleanField(name: String): Boolean? {
    return runCatching { get(name)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }.getOrNull()
}
