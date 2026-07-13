package com.ayagmar.pimobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ayagmar.pimobile.chat.ChatViewModel
import com.ayagmar.pimobile.corerpc.AvailableModel
import com.ayagmar.pimobile.corerpc.SessionStats
import com.ayagmar.pimobile.sessions.ModelInfo
import com.ayagmar.pimobile.sessions.SessionTreeEntry
import com.ayagmar.pimobile.sessions.SessionTreeSnapshot

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun SessionStatsSheet(
    isVisible: Boolean,
    stats: SessionStats?,
    sessionName: String?,
    cwd: String?,
    sessionPath: String?,
    model: ModelInfo?,
    pendingMessageCount: Int,
    isRunActive: Boolean,
    isRetrying: Boolean,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onSync: () -> Unit,
    onCompact: () -> Unit,
    onCopyLatestResponse: () -> Unit,
    onExportSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isVisible) return

    val clipboardManager = LocalClipboardManager.current
    val path = sessionPath ?: stats?.sessionPath
    val status =
        when {
            isRetrying -> HandoffRunStatus.RETRYING
            isRunActive -> HandoffRunStatus.WORKING
            else -> HandoffRunStatus.IDLE
        }
    val summary =
        formatHandoffSummary(
            HandoffSummaryData(
                sessionName = sessionName,
                cwd = cwd,
                sessionPath = path,
                model = model?.let { "${it.provider}/${it.id}" },
                runStatus = status,
            ),
        )

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Session details", style = MaterialTheme.typography.titleLarge)
            StatsSection(title = "Session") {
                sessionName?.let { StatRow("Name", it) }
                cwd?.let { StatRow("Working directory", it) }
                model?.let { StatRow("Model", "${it.provider}/${it.id}") }
                StatRow("Status", status.label)
                StatRow("Queued", pendingMessageCount.toString())
                path?.let { StatRow("Session file", it.takeLast(SESSION_PATH_DISPLAY_LENGTH)) }
            }
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else if (stats != null) {
                StatsSection(title = "Usage") {
                    StatRow("Input tokens", formatNumber(stats.inputTokens))
                    StatRow("Output tokens", formatNumber(stats.outputTokens))
                    StatRow("Messages", stats.messageCount.toString())
                    StatRow("Total cost", formatCost(stats.totalCost))
                }
            }
            Text("Handoff to computer", style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(summary, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { clipboardManager.setText(AnnotatedString(summary)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Text("Copy handoff summary")
            }
            TextButton(onClick = onCopyLatestResponse) { Text("Copy latest response") }
            TextButton(onClick = onExportSession) { Text("Export conversation/session") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSync) { Text("Sync now") }
                TextButton(onClick = onRefresh) { Text("Refresh stats") }
                TextButton(onClick = onCompact) { Text("Compact") }
            }
        }
    }
}

@Composable
private fun StatsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun ModelPickerSheet(
    isVisible: Boolean,
    models: List<AvailableModel>,
    currentModel: ModelInfo?,
    query: String,
    isLoading: Boolean,
    onQueryChange: (String) -> Unit,
    onSelectModel: (AvailableModel) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isVisible) return

    val filteredModels =
        remember(models, query) {
            if (query.isBlank()) {
                models
            } else {
                models.filter { model ->
                    model.name.contains(query, ignoreCase = true) ||
                        model.provider.contains(query, ignoreCase = true) ||
                        model.id.contains(query, ignoreCase = true)
                }
            }
        }

    val groupedModels =
        remember(filteredModels) {
            filteredModels.groupBy { it.provider }
        }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val selectedModelIndex =
        remember(groupedModels, currentModel) {
            if (currentModel == null) {
                -1
            } else {
                var index = 0
                var foundIndex = -1
                groupedModels.forEach { (_, modelsInGroup) ->
                    index += 1 // provider header item
                    modelsInGroup.forEach { model ->
                        if (
                            foundIndex < 0 &&
                            model.id == currentModel.id &&
                            model.provider == currentModel.provider
                        ) {
                            foundIndex = index
                        }
                        index += 1
                    }
                }
                foundIndex
            }
        }

    LaunchedEffect(selectedModelIndex, isVisible) {
        if (isVisible && selectedModelIndex >= 0) {
            listState.scrollToItem((selectedModelIndex - MODEL_PICKER_SCROLL_OFFSET_ITEMS).coerceAtLeast(0))
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Model") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search models...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                        )
                    },
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (filteredModels.isEmpty()) {
                    Text(
                        text = "No models found",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        groupedModels.forEach { (provider, modelsInGroup) ->
                            item {
                                Text(
                                    text = provider.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                            items(
                                items = modelsInGroup,
                                key = { model -> "${model.provider}:${model.id}" },
                            ) { model ->
                                ModelItem(
                                    model = model,
                                    isSelected =
                                        currentModel?.id == model.id &&
                                            currentModel.provider == model.provider,
                                    onClick = { onSelectModel(model) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Suppress("LongMethod")
@Composable
private fun ModelItem(
    model: AvailableModel,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { onClick() },
        colors =
            if (isSelected) {
                androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                )
            } else {
                androidx.compose.material3.CardDefaults.cardColors()
            },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (model.supportsThinking) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                "Thinking",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                model.contextWindow?.let { ctx ->
                    Text(
                        text = "Context: ${formatNumber(ctx.toLong())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                model.inputCostPer1k?.let { cost ->
                    Text(
                        text = "In: \$${String.format(java.util.Locale.US, "%.4f", cost)}/1k",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                model.outputCostPer1k?.let { cost ->
                    Text(
                        text = "Out: \$${String.format(java.util.Locale.US, "%.4f", cost)}/1k",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun TreeNavigationSheet(
    isVisible: Boolean,
    tree: SessionTreeSnapshot?,
    selectedFilter: String,
    isLoading: Boolean,
    errorMessage: String?,
    onFilterChange: (String) -> Unit,
    onForkFromEntry: (String) -> Unit,
    onJumpAndContinue: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isVisible) return

    val entries = tree?.entries.orEmpty()
    val depthByEntry = remember(entries) { computeDepthMap(entries) }
    val childCountByEntry = remember(entries) { computeChildCountMap(entries) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Session tree") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                tree?.sessionPath?.let { sessionPath ->
                    Text(
                        text = truncatePath(sessionPath),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                // Scrollable filter chips to avoid overflow
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        items = TREE_FILTER_OPTIONS,
                        key = { (filter, _) -> filter },
                    ) { (filter, label) ->
                        FilterChip(
                            selected = filter == selectedFilter,
                            onClick = { onFilterChange(filter) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    errorMessage != null -> {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    entries.isEmpty() -> {
                        Text(
                            text = "No tree data available",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    else -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(
                                items = entries,
                                key = { entry -> entry.entryId },
                            ) { entry ->
                                TreeEntryRow(
                                    entry = entry,
                                    depth = depthByEntry[entry.entryId] ?: 0,
                                    childCount = childCountByEntry[entry.entryId] ?: 0,
                                    isCurrent = tree?.currentLeafId == entry.entryId,
                                    onForkFromEntry = onForkFromEntry,
                                    onJumpAndContinue = onJumpAndContinue,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Suppress("MagicNumber", "LongMethod", "LongParameterList")
@Composable
private fun TreeEntryRow(
    entry: SessionTreeEntry,
    depth: Int,
    childCount: Int,
    isCurrent: Boolean,
    onForkFromEntry: (String) -> Unit,
    onJumpAndContinue: (String) -> Unit,
) {
    val indent = (depth * 8).dp
    val isMessage = entry.entryType == "message"
    val containerColor =
        when {
            isCurrent -> MaterialTheme.colorScheme.primaryContainer
            isMessage -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        }
    val contentColor =
        when {
            isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        }

    Card(
        modifier = Modifier.fillMaxWidth().padding(start = indent),
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val typeIcon = treeEntryIcon(entry.entryType)
                    Icon(
                        imageVector = typeIcon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = contentColor.copy(alpha = 0.7f),
                    )
                    val label =
                        buildString {
                            append(entry.entryType.replace('_', ' '))
                            entry.role?.let { append(" · $it") }
                        }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                    )
                }

                if (isCurrent) {
                    Text(
                        text = "● current",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (isMessage) {
                Text(
                    text = entry.preview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    color = contentColor,
                )
            }

            if (entry.isBookmarked && !entry.label.isNullOrBlank()) {
                Text(
                    text = "🔖 ${entry.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (childCount > 1) {
                    Text(
                        text = "↳ $childCount branches",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    TextButton(
                        onClick = { onJumpAndContinue(entry.entryId) },
                        contentPadding =
                            androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 8.dp,
                                vertical = 0.dp,
                            ),
                    ) {
                        Text("Jump", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = { onForkFromEntry(entry.entryId) },
                        contentPadding =
                            androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 8.dp,
                                vertical = 0.dp,
                            ),
                    ) {
                        Text("Fork", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun treeEntryIcon(entryType: String): ImageVector {
    return when (entryType) {
        "message" -> Icons.Default.Description
        "model_change" -> Icons.Default.Refresh
        "thinking_level_change" -> Icons.Default.Menu
        else -> Icons.Default.PlayArrow
    }
}

@Suppress("ReturnCount")
private fun computeDepthMap(entries: List<SessionTreeEntry>): Map<String, Int> {
    val byId = entries.associateBy { it.entryId }
    val memo = mutableMapOf<String, Int>()

    fun depth(
        entryId: String,
        stack: MutableSet<String>,
    ): Int {
        memo[entryId]?.let { return it }
        if (!stack.add(entryId)) {
            return 0
        }

        val entry = byId[entryId]
        val resolvedDepth =
            when {
                entry == null -> 0
                entry.parentId == null -> 0
                else -> depth(entry.parentId, stack) + 1
            }

        stack.remove(entryId)
        memo[entryId] = resolvedDepth
        return resolvedDepth
    }

    entries.forEach { entry -> depth(entry.entryId, mutableSetOf()) }
    return memo
}

private fun computeChildCountMap(entries: List<SessionTreeEntry>): Map<String, Int> {
    return entries
        .groupingBy { it.parentId }
        .eachCount()
        .mapNotNull { (parentId, count) ->
            parentId?.let { it to count }
        }.toMap()
}

private val TREE_FILTER_OPTIONS =
    listOf(
        ChatViewModel.TREE_FILTER_DEFAULT to "default",
        ChatViewModel.TREE_FILTER_ALL to "all",
        ChatViewModel.TREE_FILTER_NO_TOOLS to "no-tools",
        ChatViewModel.TREE_FILTER_USER_ONLY to "user-only",
        ChatViewModel.TREE_FILTER_LABELED_ONLY to "labeled-only",
    )

private const val MODEL_PICKER_SCROLL_OFFSET_ITEMS = 1
private const val SESSION_PATH_DISPLAY_LENGTH = 40

private fun truncatePath(path: String): String {
    if (path.length <= SESSION_PATH_DISPLAY_LENGTH) {
        return path
    }
    val head = SESSION_PATH_DISPLAY_LENGTH / 2
    val tail = SESSION_PATH_DISPLAY_LENGTH - head - 1
    return "${path.take(head)}…${path.takeLast(tail)}"
}
