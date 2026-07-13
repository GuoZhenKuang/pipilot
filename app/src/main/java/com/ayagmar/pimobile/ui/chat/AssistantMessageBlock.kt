package com.ayagmar.pimobile.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

internal sealed interface AssistantMessageBlock {
    data class Paragraph(
        val text: String,
    ) : AssistantMessageBlock

    data class Code(
        val code: String,
        val language: String?,
    ) : AssistantMessageBlock
}

internal fun parseAssistantMessageBlocks(text: String): List<AssistantMessageBlock> {
    if (text.isBlank()) return emptyList()

    val blocks = mutableListOf<AssistantMessageBlock>()
    var cursor = 0

    CODE_FENCE_REGEX.findAll(text).forEach { match ->
        val matchStart = match.range.first
        val matchEndExclusive = match.range.last + 1

        if (matchStart > cursor) {
            val paragraph = text.substring(cursor, matchStart).trim()
            if (paragraph.isNotEmpty()) {
                blocks += AssistantMessageBlock.Paragraph(paragraph)
            }
        }

        val language = match.groupValues[1].takeIf { it.isNotBlank() }
        val code = match.groupValues[2]
        blocks += AssistantMessageBlock.Code(code = code.trimEnd(), language = language)
        cursor = matchEndExclusive
    }

    if (cursor < text.length) {
        val paragraph = text.substring(cursor).trim()
        if (paragraph.isNotEmpty()) {
            blocks += AssistantMessageBlock.Paragraph(paragraph)
        }
    }

    return blocks
}

internal fun highlightCodeBlock(
    code: String,
    language: String?,
    colors: androidx.compose.material3.ColorScheme,
): AnnotatedString {
    val text = code.ifBlank { "(empty code block)" }
    val commentPattern = commentRegexFor(language)
    val keywordPattern = keywordRegexFor(language)

    val commentStyle = SpanStyle(color = colors.outline)
    val stringStyle = SpanStyle(color = colors.tertiary)
    val numberStyle = SpanStyle(color = colors.secondary)
    val keywordStyle = SpanStyle(color = colors.primary)

    return buildAnnotatedString {
        append(text)

        applyStyle(STRING_REGEX, stringStyle, text)
        applyStyle(NUMBER_REGEX, numberStyle, text)
        applyStyle(keywordPattern, keywordStyle, text)
        applyStyle(commentPattern, commentStyle, text)
    }
}

private fun AnnotatedString.Builder.applyStyle(
    regex: Regex,
    style: SpanStyle,
    text: String,
) {
    regex.findAll(text).forEach { match ->
        addStyle(style, match.range.first, match.range.last + 1)
    }
}

private fun keywordRegexFor(language: String?): Regex {
    return when (language?.lowercase()) {
        "kotlin", "kt" -> KOTLIN_KEYWORD_REGEX
        "java" -> JAVA_KEYWORD_REGEX
        "python", "py" -> PYTHON_KEYWORD_REGEX
        "javascript", "js", "typescript", "ts", "tsx" -> JS_TS_KEYWORD_REGEX
        "bash", "shell", "sh" -> BASH_KEYWORD_REGEX
        else -> GENERIC_KEYWORD_REGEX
    }
}

private fun commentRegexFor(language: String?): Regex {
    return when (language?.lowercase()) {
        "python", "py", "bash", "shell", "sh", "yaml", "yml" -> HASH_COMMENT_REGEX
        else -> SLASH_COMMENT_REGEX
    }
}
