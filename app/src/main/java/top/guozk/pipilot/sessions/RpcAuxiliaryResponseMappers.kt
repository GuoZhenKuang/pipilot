package top.guozk.pipilot.sessions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.guozk.pipilot.corerpc.AvailableModel
import top.guozk.pipilot.corerpc.BashResult
import top.guozk.pipilot.corerpc.SessionStats
import kotlin.math.roundToInt

internal fun parseBashResult(data: JsonObject?): BashResult {
    return BashResult(
        output = data?.stringField("output") ?: "",
        exitCode = data?.get("exitCode")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1,
        // pi RPC uses "truncated" and "fullOutputPath".
        wasTruncated = data?.booleanField("truncated") ?: data?.booleanField("wasTruncated") ?: false,
        fullLogPath = data?.stringField("fullOutputPath") ?: data?.stringField("fullLogPath"),
    )
}

@Suppress("MagicNumber", "LongMethod")
internal fun parseSessionStats(data: JsonObject?): SessionStats {
    val tokens = runCatching { data?.get("tokens")?.jsonObject }.getOrNull()

    val inputTokens =
        coalesceLong(
            tokens?.longField("input"),
            data?.longField("inputTokens"),
        )
    val outputTokens =
        coalesceLong(
            tokens?.longField("output"),
            data?.longField("outputTokens"),
        )
    val cacheReadTokens =
        coalesceLong(
            tokens?.longField("cacheRead"),
            data?.longField("cacheReadTokens"),
        )
    val cacheWriteTokens =
        coalesceLong(
            tokens?.longField("cacheWrite"),
            data?.longField("cacheWriteTokens"),
        )
    val totalCost =
        coalesceDouble(
            data?.doubleField("cost"),
            data?.doubleField("totalCost"),
        )

    val messageCount =
        coalesceInt(
            data?.intField("totalMessages"),
            data?.intField("messageCount"),
        )
    val userMessageCount =
        coalesceInt(
            data?.intField("userMessages"),
            data?.intField("userMessageCount"),
        )
    val assistantMessageCount =
        coalesceInt(
            data?.intField("assistantMessages"),
            data?.intField("assistantMessageCount"),
        )
    val toolResultCount =
        coalesceInt(
            data?.intField("toolResults"),
            data?.intField("toolResultCount"),
            data?.intField("toolCalls"),
        )
    val sessionPath =
        coalesceString(
            data?.stringField("sessionFile"),
            data?.stringField("sessionPath"),
        )
    val compactionCount =
        coalesceInt(
            data?.intField("compactions"),
            data?.intField("compactionCount"),
            data?.intField("autoCompactions"),
        )

    val contextUsage = runCatching { data?.get("contextUsage")?.jsonObject }.getOrNull()
    val context = runCatching { data?.get("context")?.jsonObject }.getOrNull()
    val contextUsedTokens =
        coalesceLongOrNull(
            contextUsage?.longField("tokens"),
            context?.longField("used"),
            context?.longField("tokens"),
            context?.longField("current"),
            data?.longField("contextUsedTokens"),
            data?.longField("contextTokens"),
            data?.longField("activeContextTokens"),
        )
    val contextWindowTokens =
        coalesceLongOrNull(
            contextUsage?.longField("contextWindow"),
            context?.longField("window"),
            context?.longField("max"),
            data?.longField("contextWindow"),
        )
    val contextUsagePercent =
        coalesceIntOrNull(
            contextUsage?.intField("percent"),
            contextUsage?.doubleField("percent")?.roundToInt(),
            context?.intField("percent"),
            context?.doubleField("percent")?.roundToInt(),
            data?.intField("contextPercent"),
            data?.doubleField("contextPercent")?.roundToInt(),
            data?.intField("contextUsagePercent"),
            data?.doubleField("contextUsagePercent")?.roundToInt(),
        )

    return SessionStats(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens,
        totalCost = totalCost,
        messageCount = messageCount,
        userMessageCount = userMessageCount,
        assistantMessageCount = assistantMessageCount,
        toolResultCount = toolResultCount,
        sessionPath = sessionPath,
        compactionCount = compactionCount,
        contextUsedTokens = contextUsedTokens,
        contextWindowTokens = contextWindowTokens,
        contextUsagePercent = contextUsagePercent,
    )
}

internal fun parseAvailableModels(data: JsonObject?): List<AvailableModel> {
    val models = runCatching { data?.get("models")?.jsonArray }.getOrNull() ?: JsonArray(emptyList())

    return models.mapNotNull { modelElement ->
        val modelObject = runCatching { modelElement.jsonObject }.getOrNull() ?: return@mapNotNull null
        val id = modelObject.stringField("id") ?: return@mapNotNull null
        val cost = runCatching { modelObject["cost"]?.jsonObject }.getOrNull()

        AvailableModel(
            id = id,
            name = modelObject.stringField("name") ?: id,
            provider = modelObject.stringField("provider") ?: "unknown",
            contextWindow = modelObject.intField("contextWindow"),
            maxOutputTokens = modelObject.intField("maxTokens") ?: modelObject.intField("maxOutputTokens"),
            supportsThinking =
                modelObject.booleanField("reasoning")
                    ?: modelObject.booleanField("supportsThinking")
                    ?: false,
            inputCostPer1k = cost?.doubleField("input") ?: modelObject.doubleField("inputCostPer1k"),
            outputCostPer1k = cost?.doubleField("output") ?: modelObject.doubleField("outputCostPer1k"),
        )
    }
}

internal fun coalesceLong(vararg values: Long?): Long {
    return values.firstOrNull { it != null } ?: 0L
}

internal fun coalesceLongOrNull(vararg values: Long?): Long? {
    return values.firstOrNull { it != null }
}

internal fun coalesceInt(vararg values: Int?): Int {
    return values.firstOrNull { it != null } ?: 0
}

internal fun coalesceIntOrNull(vararg values: Int?): Int? {
    return values.firstOrNull { it != null }
}

internal fun coalesceDouble(vararg values: Double?): Double {
    return values.firstOrNull { it != null } ?: 0.0
}

internal fun coalesceString(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }
}

private fun JsonObject?.stringField(fieldName: String): String? {
    return runCatching { this?.get(fieldName)?.jsonPrimitive?.contentOrNull }.getOrNull()
}

private fun JsonObject?.booleanField(fieldName: String): Boolean? {
    return runCatching { this?.get(fieldName)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }.getOrNull()
}

private fun JsonObject?.longField(fieldName: String): Long? {
    val value = this?.get(fieldName)?.jsonPrimitive?.contentOrNull ?: return null
    return value.toLongOrNull()
}

private fun JsonObject?.intField(fieldName: String): Int? {
    val value = this?.get(fieldName)?.jsonPrimitive?.contentOrNull ?: return null
    return value.toIntOrNull()
}

private fun JsonObject?.doubleField(fieldName: String): Double? {
    val value = this?.get(fieldName)?.jsonPrimitive?.contentOrNull ?: return null
    return value.toDoubleOrNull()
}
