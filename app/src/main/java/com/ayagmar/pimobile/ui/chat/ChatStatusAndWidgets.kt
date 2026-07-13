package com.ayagmar.pimobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ayagmar.pimobile.chat.ExtensionWidget
import com.ayagmar.pimobile.sessions.ModelInfo

@Suppress("LongMethod")
@Composable
internal fun ModelThinkingControls(
    currentModel: ModelInfo?,
    thinkingLevel: String?,
    onSetThinkingLevel: (String) -> Unit,
    onShowModelPicker: () -> Unit,
) {
    var showThinkingMenu by remember { mutableStateOf(false) }

    val modelText = currentModel?.name ?: "Select model"
    val thinkingText = thinkingLevel?.uppercase() ?: "OFF"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onShowModelPicker,
            modifier = Modifier.weight(1f),
            contentPadding =
                androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 6.dp,
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = modelText,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }

        // Thinking level selector
        Box(modifier = Modifier.wrapContentWidth()) {
            OutlinedButton(
                onClick = { showThinkingMenu = true },
                contentPadding =
                    androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = 6.dp,
                    ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = thinkingText,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            DropdownMenu(
                expanded = showThinkingMenu,
                onDismissRequest = { showThinkingMenu = false },
            ) {
                THINKING_LEVEL_OPTIONS.forEach { level ->
                    DropdownMenuItem(
                        text = { Text(level.replaceFirstChar { it.uppercase() }) },
                        onClick = {
                            onSetThinkingLevel(level)
                            showThinkingMenu = false
                        },
                    )
                }
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
internal fun ExtensionStatusStrip(statuses: Map<String, String>) {
    if (statuses.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    var previousStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var hasPreviousSnapshot by remember { mutableStateOf(false) }

    val comparisonSnapshot =
        if (hasPreviousSnapshot) {
            previousStatuses
        } else {
            statuses.mapValues { (_, value) -> value.trim() }
        }

    val presentation =
        remember(statuses, comparisonSnapshot, expanded) {
            buildExtensionStatusPresentation(
                statuses = statuses,
                previousStatuses = comparisonSnapshot,
                expanded = expanded,
            )
        }

    LaunchedEffect(statuses) {
        previousStatuses = statuses.mapValues { (_, value) -> value.trim() }
        hasPreviousSnapshot = true
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "Extension status",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${presentation.activeCount} active · ${presentation.quietCount} quiet",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }

            if (!expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val first = presentation.visibleEntries.firstOrNull()
                    if (first != null) {
                        StatusPill(entry = first)
                    }
                    if (presentation.hiddenCount > 0) {
                        Text(
                            text = "+${presentation.hiddenCount} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    presentation.visibleEntries.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = if (entry.isChanged) "•" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Text(
                                text = "${entry.key}: ${entry.value.take(STATUS_VALUE_MAX_LENGTH)}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            if (presentation.changedCount > 0) {
                Text(
                    text = "${presentation.changedCount} update(s) since last refresh",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(entry: ExtensionStatusEntry) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color =
            if (entry.isLowSignal) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
    ) {
        Text(
            text = "${entry.key}: ${entry.value.take(EXTENSION_STATUS_PILL_MAX_LENGTH)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

internal data class ExtensionStatusEntry(
    val key: String,
    val value: String,
    val isLowSignal: Boolean,
    val isChanged: Boolean,
)

internal data class ExtensionStatusPresentation(
    val visibleEntries: List<ExtensionStatusEntry>,
    val hiddenCount: Int,
    val activeCount: Int,
    val quietCount: Int,
    val changedCount: Int,
)

internal fun buildExtensionStatusPresentation(
    statuses: Map<String, String>,
    previousStatuses: Map<String, String>,
    expanded: Boolean,
): ExtensionStatusPresentation {
    if (statuses.isEmpty()) {
        return ExtensionStatusPresentation(
            visibleEntries = emptyList(),
            hiddenCount = 0,
            activeCount = 0,
            quietCount = 0,
            changedCount = 0,
        )
    }

    val entries =
        statuses
            .toSortedMap()
            .map { (key, rawValue) ->
                val value = rawValue.trim().ifEmpty { "(empty)" }
                ExtensionStatusEntry(
                    key = key,
                    value = value,
                    isLowSignal = isLowSignalExtensionStatus(value),
                    isChanged = previousStatuses[key] != value,
                )
            }

    val changed = entries.filter { it.isChanged }
    val active = entries.filterNot { it.isLowSignal }
    val quietCount = entries.size - active.size

    val compactCandidates =
        when {
            changed.isNotEmpty() -> changed
            active.isNotEmpty() -> active
            else -> entries
        }

    val visibleEntries = if (expanded) entries else compactCandidates.take(MAX_COMPACT_EXTENSION_STATUS_ITEMS)

    return ExtensionStatusPresentation(
        visibleEntries = visibleEntries,
        hiddenCount = if (expanded) 0 else (entries.size - visibleEntries.size).coerceAtLeast(0),
        activeCount = active.size,
        quietCount = quietCount,
        changedCount = changed.size,
    )
}

internal fun isLowSignalExtensionStatus(value: String): Boolean {
    val normalized = value.trim().lowercase()
    return LOW_SIGNAL_STATUS_TOKENS.any { token -> normalized.contains(token) }
}

@Composable
internal fun ExtensionWidgets(
    widgets: Map<String, ExtensionWidget>,
    placement: String,
) {
    val matchingWidgets = widgets.values.filter { it.placement == placement }

    matchingWidgets.forEach { widget ->
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
            ) {
                widget.lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal const val CHAT_PROMPT_CONTROLS_TAG = "chat_prompt_controls"
internal const val CHAT_STREAMING_CONTROLS_TAG = "chat_streaming_controls"
internal const val CHAT_PROMPT_INPUT_ROW_TAG = "chat_prompt_input_row"
internal const val CHAT_RUN_PROGRESS_TAG = "chat_run_progress"
internal const val CHAT_JUMP_TO_LATEST_TAG = "chat_jump_to_latest"

internal const val COLLAPSED_OUTPUT_LENGTH = 280
internal const val THINKING_COLLAPSE_THRESHOLD = 280
internal const val MAX_ARG_DISPLAY_LENGTH = 100
internal const val MAX_INLINE_USER_IMAGE_PREVIEWS = 4
internal const val USER_IMAGE_PREVIEW_SIZE_DP = 56
internal const val AUTO_SCROLL_BOTTOM_THRESHOLD_ITEMS = 2
internal const val AUTO_SCROLL_ANIMATION_MIN_INTERVAL_MS = 120L
internal const val STREAMING_AUTO_SCROLL_CHECK_INTERVAL_MS = 90L
internal const val CHAT_TIMELINE_BOTTOM_ANCHOR_KEY = "chat_timeline_bottom_anchor"
internal const val TOOL_HIGHLIGHT_MAX_LENGTH = 1_000
internal const val STATUS_VALUE_MAX_LENGTH = 180
internal const val EXTENSION_STATUS_PILL_MAX_LENGTH = 56
internal const val MAX_COMPACT_EXTENSION_STATUS_ITEMS = 2
internal const val RUN_PROGRESS_TICK_MS = 1_000L
internal const val STREAMING_FRAME_LOG_TAG = "StreamingFrameMetrics"
internal val THINKING_LEVEL_OPTIONS = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
internal val CODE_FENCE_REGEX = Regex("```([\\w+-]*)\\r?\\n([\\s\\S]*?)```")
internal val STRING_REGEX = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'")
internal val NUMBER_REGEX = Regex("\\b\\d+(?:\\.\\d+)?\\b")
internal val HASH_COMMENT_REGEX = Regex("#.*$", setOf(RegexOption.MULTILINE))
internal val SLASH_COMMENT_REGEX =
    Regex(
        "//.*$|/\\*.*?\\*/",
        setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
    )
internal val KOTLIN_KEYWORD_REGEX =
    Regex(
        "\\b(class|object|interface|fun|val|var|when|if|else|return|suspend|data|sealed|" +
            "private|public|override|import|package)\\b",
    )
internal val JAVA_KEYWORD_REGEX =
    Regex(
        "\\b(class|interface|enum|public|private|protected|static|final|void|return|if|" +
            "else|switch|case|new|import|package)\\b",
    )
internal val PYTHON_KEYWORD_REGEX =
    Regex(
        "\\b(def|class|import|from|as|if|elif|else|for|while|return|try|except|with|lambda|pass|break|continue)\\b",
    )
internal val JS_TS_KEYWORD_REGEX =
    Regex(
        "\\b(function|class|const|let|var|return|if|else|switch|case|import|from|export|async|await|interface|type)\\b",
    )
internal val BASH_KEYWORD_REGEX = Regex("\\b(if|then|fi|for|do|done|case|esac|function|export|echo)\\b")
internal val GENERIC_KEYWORD_REGEX =
    Regex("\\b(if|else|for|while|return|class|function|import|from|const|let|var|def|public|private)\\b")
internal val TOOL_OUTPUT_LANGUAGE_BY_EXTENSION =
    mapOf(
        "kt" to "kotlin",
        "kts" to "kotlin",
        "java" to "java",
        "js" to "javascript",
        "jsx" to "javascript",
        "ts" to "typescript",
        "tsx" to "typescript",
        "py" to "python",
        "json" to "json",
        "jsonl" to "json",
        "xml" to "xml",
        "html" to "xml",
        "svg" to "xml",
        "sh" to "bash",
        "bash" to "bash",
        "sql" to "sql",
        "yml" to "yaml",
        "yaml" to "yaml",
        "go" to "go",
        "rs" to "rust",
        "md" to "markdown",
    )
internal val LOW_SIGNAL_STATUS_TOKENS =
    setOf(
        "idle",
        "ready",
        "ok",
        "connected",
        "none",
        "no updates",
        "synced",
    )
