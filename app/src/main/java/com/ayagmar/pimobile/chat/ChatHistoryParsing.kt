package com.ayagmar.pimobile.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal fun historyWindowSignature(messages: List<JsonObject>): String {
    if (messages.isEmpty()) return "empty"

    val marker =
        messages
            .joinToString(separator = "|") { message ->
                val role = message.stringField("role").orEmpty()
                val entryId = message.stringField("entryId").orEmpty()
                "$role:$entryId:${message.toString().hashCode()}"
            }

    return "${messages.size}:$marker"
}

internal fun extractHistoryMessageWindow(data: JsonObject?): HistoryMessageWindow {
    val rawMessages = runCatching { data?.get("messages")?.jsonArray }.getOrNull() ?: JsonArray(emptyList())
    val startIndex = (rawMessages.size - HISTORY_WINDOW_MAX_ITEMS).coerceAtLeast(0)

    val messages =
        rawMessages
            .drop(startIndex)
            .mapNotNull { messageElement ->
                runCatching { messageElement.jsonObject }.getOrNull()
            }

    return HistoryMessageWindow(
        messages = messages,
        absoluteOffset = startIndex,
    )
}

internal fun parseHistoryItems(
    messages: List<JsonObject>,
    absoluteIndexOffset: Int,
    startIndex: Int = 0,
    endExclusive: Int = messages.size,
): List<ChatTimelineItem> {
    if (messages.isEmpty()) {
        return emptyList()
    }

    val boundedStart = startIndex.coerceIn(0, messages.size)
    val boundedEnd = endExclusive.coerceIn(boundedStart, messages.size)

    return (boundedStart until boundedEnd).mapNotNull { index ->
        val message = messages[index]
        val absoluteIndex = absoluteIndexOffset + index

        when (message.stringField("role")) {
            "user" -> {
                val content = message["content"]
                val text = extractUserText(content)
                val imageCount = extractUserImageCount(content)
                ChatTimelineItem.User(
                    id = "history-user-$absoluteIndex",
                    text = text,
                    imageCount = imageCount,
                )
            }

            "assistant" -> {
                val text = extractAssistantText(message["content"])
                val thinking = extractAssistantThinking(message["content"])
                ChatTimelineItem.Assistant(
                    id = "history-assistant-$absoluteIndex",
                    text = text,
                    thinking = thinking,
                    isThinkingComplete = thinking != null,
                    isStreaming = false,
                )
            }

            "toolResult" -> {
                val output = extractToolOutput(message)
                ChatTimelineItem.Tool(
                    id = "history-tool-$absoluteIndex",
                    toolName = message.stringField("toolName") ?: "tool",
                    output = output,
                    isCollapsed = output.length > 400,
                    isStreaming = false,
                    isError = message.booleanField("isError") ?: false,
                    arguments = emptyMap(),
                    editDiff = null,
                )
            }

            else -> null
        }
    }
}

internal fun extractUserText(content: JsonElement?): String {
    return when (content) {
        null -> ""
        is JsonObject -> content.stringField("text").orEmpty()
        else -> {
            runCatching {
                when (content) {
                    is kotlinx.serialization.json.JsonPrimitive -> content.contentOrNull.orEmpty()
                    else -> {
                        content.jsonArray
                            .mapNotNull { block ->
                                block.jsonObject.takeIf { it.stringField("type") == "text" }?.stringField("text")
                            }.joinToString("\n")
                    }
                }
            }.getOrDefault("")
        }
    }
}

internal fun extractUserImageCount(content: JsonElement?): Int {
    return runCatching {
        when (content) {
            null -> 0
            is kotlinx.serialization.json.JsonPrimitive -> 0
            is JsonObject -> {
                val type = content.stringField("type")?.lowercase().orEmpty()
                if ("image" in type) 1 else 0
            }
            else -> {
                content.jsonArray.count { block ->
                    val blockObject = runCatching { block.jsonObject }.getOrNull() ?: return@count false
                    val type = blockObject.stringField("type")?.lowercase().orEmpty()
                    type.contains("image") ||
                        blockObject["image"] != null ||
                        blockObject["imageUrl"] != null ||
                        blockObject["image_url"] != null
                }
            }
        }
    }.getOrDefault(0)
}

internal fun extractAssistantText(content: JsonElement?): String {
    val contentArray = runCatching { content?.jsonArray }.getOrNull() ?: return ""
    return contentArray
        .mapNotNull { block ->
            val blockObject = block.jsonObject
            if (blockObject.stringField("type") == "text") {
                blockObject.stringField("text")
            } else {
                null
            }
        }.joinToString("\n")
}

internal fun extractAssistantThinking(content: JsonElement?): String? {
    val contentArray = runCatching { content?.jsonArray }.getOrNull() ?: return null
    val thinkingBlocks =
        contentArray
            .mapNotNull { block ->
                val blockObject = block.jsonObject
                if (blockObject.stringField("type") == "thinking") {
                    blockObject.stringField("thinking")
                } else {
                    null
                }
            }
    return thinkingBlocks.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

internal fun extractToolOutput(source: JsonObject?): String {
    return source?.let { jsonSource ->
        val fromContent =
            runCatching {
                jsonSource["content"]?.jsonArray
                    ?.mapNotNull { block ->
                        val blockObject = block.jsonObject
                        if (blockObject.stringField("type") == "text") {
                            blockObject.stringField("text")
                        } else {
                            null
                        }
                    }?.joinToString("\n")
            }.getOrNull()

        fromContent?.takeIf { it.isNotBlank() } ?: jsonSource.stringField("output").orEmpty()
    }.orEmpty()
}
