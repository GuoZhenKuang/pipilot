package com.ayagmar.pimobile.sessions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun parseRpcSessionTreeSnapshot(
    data: JsonObject?,
    sessionPath: String,
    filter: String?,
): SessionTreeSnapshot {
    val entries = mutableListOf<SessionTreeEntry>()
    val roots = runCatching { data?.get("tree")?.jsonArray }.getOrNull() ?: JsonArray(emptyList())
    roots.forEach { root -> flattenRpcTreeNode(root.jsonObject, entries) }

    val visibleEntries = filterSessionTreeEntries(entries, filter ?: "default")
    val visibleIds = visibleEntries.mapTo(mutableSetOf()) { entry -> entry.entryId }
    val parents = entries.associate { entry -> entry.entryId to entry.parentId }

    return SessionTreeSnapshot(
        sessionPath = sessionPath,
        rootIds =
            visibleEntries
                .filter { entry -> entry.parentId == null || entry.parentId !in visibleIds }
                .map { entry -> entry.entryId },
        currentLeafId = resolveVisibleTreeLeaf(data.stringField("leafId"), visibleIds, parents),
        entries = visibleEntries,
    )
}

private fun flattenRpcTreeNode(
    node: JsonObject,
    destination: MutableList<SessionTreeEntry>,
) {
    val entry = runCatching { node["entry"]?.jsonObject }.getOrNull() ?: error("会话树节点缺少 entry")
    val entryId = entry.stringField("id") ?: error("会话树条目缺少 id")
    val entryType = entry.stringField("type") ?: error("会话树条目缺少 type")
    val message = runCatching { entry["message"]?.jsonObject }.getOrNull()
    val label = node.stringField("label")

    destination +=
        SessionTreeEntry(
            entryId = entryId,
            parentId = entry.stringField("parentId"),
            entryType = entryType,
            role = if (entryType == "custom_message") "custom" else message.stringField("role"),
            timestamp = entry.stringField("timestamp"),
            preview = rpcTreeEntryPreview(entry, message),
            label = label,
            isBookmarked = label != null,
        )

    val children = runCatching { node["children"]?.jsonArray }.getOrNull() ?: JsonArray(emptyList())
    children.forEach { child -> flattenRpcTreeNode(child.jsonObject, destination) }
}

private fun rpcTreeEntryPreview(
    entry: JsonObject,
    message: JsonObject?,
): String {
    val content = message?.get("content")
    val text =
        when (content) {
            is JsonPrimitive -> content.contentOrNull
            is JsonArray ->
                content.firstNotNullOfOrNull { block ->
                    runCatching { block.jsonObject.stringField("text") }.getOrNull()
                }
            else -> null
        }

    return text ?: entry.stringField("summary") ?: entry.stringField("name") ?: entry.stringField("type") ?: "entry"
}

private fun filterSessionTreeEntries(
    entries: List<SessionTreeEntry>,
    filter: String,
): List<SessionTreeEntry> {
    return when (filter) {
        "default" -> entries.filter { entry -> entry.entryType != "label" && entry.entryType != "custom" }
        "all" -> entries
        "no-tools" -> entries.filter { entry -> entry.role != "toolResult" }
        "user-only" -> entries.filter { entry -> entry.role == "user" }
        "labeled-only" -> entries.filter { entry -> entry.isBookmarked || entry.entryType == "label" }
        else -> error("不支持的会话树筛选器：$filter")
    }
}

private fun resolveVisibleTreeLeaf(
    leafId: String?,
    visibleIds: Set<String>,
    parents: Map<String, String?>,
): String? {
    var current = leafId
    val visited = mutableSetOf<String>()
    while (current != null && visited.add(current)) {
        if (current in visibleIds) return current
        current = parents[current]
    }
    return null
}

private fun JsonObject?.stringField(fieldName: String): String? {
    return runCatching { this?.get(fieldName)?.jsonPrimitive?.contentOrNull }.getOrNull()
}
