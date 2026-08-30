package top.guozk.pipilot.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.guozk.pipilot.sessions.ModelInfo

internal fun JsonObject.stringField(fieldName: String): String? {
    return this[fieldName]?.jsonPrimitive?.contentOrNull
}

internal fun JsonObject.booleanField(fieldName: String): Boolean? {
    return this[fieldName]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
}

internal fun JsonObject.intField(fieldName: String): Int? {
    return this[fieldName]?.jsonPrimitive?.intOrNull
}

internal fun JsonObject?.deliveryModeField(
    camelCaseKey: String,
    snakeCaseKey: String,
): String {
    val value =
        this?.get(camelCaseKey)?.jsonPrimitive?.contentOrNull
            ?: this?.get(snakeCaseKey)?.jsonPrimitive?.contentOrNull

    return value?.takeIf {
        it == ChatViewModel.DELIVERY_MODE_ALL || it == ChatViewModel.DELIVERY_MODE_ONE_AT_A_TIME
    } ?: ChatViewModel.DELIVERY_MODE_ONE_AT_A_TIME
}

internal fun parseModelInfo(data: JsonObject?): ModelInfo? {
    val model = data?.get("model") as? JsonObject ?: return null
    return ModelInfo(
        id = model.stringField("id") ?: "unknown",
        name = model.stringField("name") ?: "未知模型",
        provider = model.stringField("provider") ?: "unknown",
        thinkingLevel = data.stringField("thinkingLevel") ?: "off",
        contextWindow = model.intField("contextWindow"),
    )
}

/**
 * Extracts tool arguments from JSON object as a map of string keys to string values.
 * Only extracts primitive string arguments for display purposes.
 */
internal fun extractToolArguments(args: JsonObject?): Map<String, String> {
    if (args == null) return emptyMap()
    return args
        .mapNotNull { (key, value) ->
            when {
                value is kotlinx.serialization.json.JsonPrimitive &&
                    value.isString -> key to value.content
                else -> null
            }
        }.toMap()
}

/**
 * Extracts edit tool diff information from arguments.
 * Returns null if not an edit tool or required fields are missing.
 */
@Suppress("ReturnCount")
internal fun extractEditDiff(args: JsonObject?): EditDiffInfo? {
    if (args == null) return null
    val path = args.stringField("path") ?: return null
    val oldString = args.stringField("oldString") ?: return null
    val newString = args.stringField("newString") ?: return null
    return EditDiffInfo(
        path = path,
        oldString = oldString,
        newString = newString,
    )
}
