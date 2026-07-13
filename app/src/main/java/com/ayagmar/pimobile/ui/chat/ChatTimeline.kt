package com.ayagmar.pimobile.ui.chat

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ayagmar.pimobile.chat.ChatTimelineItem
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Suppress("LongParameterList")
@Composable
internal fun ChatBody(
    isLoading: Boolean,
    timeline: List<ChatTimelineItem>,
    hasOlderMessages: Boolean,
    hiddenHistoryCount: Int,
    expandedToolArguments: Set<String>,
    isRunActive: Boolean,
    runPhase: LiveRunPhase,
    runElapsedSeconds: Long,
    callbacks: ChatCallbacks,
) {
    val hasStreamingTimelineItem =
        remember(timeline) {
            timeline.any { item ->
                when (item) {
                    is ChatTimelineItem.Assistant -> item.isStreaming
                    is ChatTimelineItem.Tool -> item.isStreaming
                    is ChatTimelineItem.User -> false
                }
            }
        }
    val showInlineRunProgress = isRunActive && !hasStreamingTimelineItem

    if (isLoading) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }
    } else if (timeline.isEmpty() && !showInlineRunProgress) {
        Text(
            text = "No chat messages yet. Resume a session and send a prompt.",
            style = MaterialTheme.typography.bodyLarge,
        )
    } else {
        ChatTimeline(
            timeline = timeline,
            hasOlderMessages = hasOlderMessages,
            hiddenHistoryCount = hiddenHistoryCount,
            expandedToolArguments = expandedToolArguments,
            isRunActive = isRunActive,
            showInlineRunProgress = showInlineRunProgress,
            runPhase = runPhase,
            runElapsedSeconds = runElapsedSeconds,
            onLoadOlderMessages = callbacks.onLoadOlderMessages,
            onToggleToolExpansion = callbacks.onToggleToolExpansion,
            onToggleThinkingExpansion = callbacks.onToggleThinkingExpansion,
            onToggleDiffExpansion = callbacks.onToggleDiffExpansion,
            onToggleToolArgumentsExpansion = callbacks.onToggleToolArgumentsExpansion,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun ChatTimeline(
    timeline: List<ChatTimelineItem>,
    hasOlderMessages: Boolean,
    hiddenHistoryCount: Int,
    expandedToolArguments: Set<String>,
    isRunActive: Boolean,
    showInlineRunProgress: Boolean,
    runPhase: LiveRunPhase,
    runElapsedSeconds: Long,
    onLoadOlderMessages: () -> Unit,
    onToggleToolExpansion: (String) -> Unit,
    onToggleThinkingExpansion: (String) -> Unit,
    onToggleDiffExpansion: (String) -> Unit,
    onToggleToolArgumentsExpansion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewImageUri by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val autoScrollUi =
        rememberTimelineAutoScrollUi(
            listState = listState,
            timeline = timeline,
            showInlineRunProgress = showInlineRunProgress,
            isRunActive = isRunActive,
        )

    Box(modifier = modifier.fillMaxWidth()) {
        ChatTimelineList(
            listState = listState,
            timeline = timeline,
            hasOlderMessages = hasOlderMessages,
            hiddenHistoryCount = hiddenHistoryCount,
            expandedToolArguments = expandedToolArguments,
            showInlineRunProgress = showInlineRunProgress,
            runPhase = runPhase,
            runElapsedSeconds = runElapsedSeconds,
            onLoadOlderMessages = onLoadOlderMessages,
            onToggleToolExpansion = onToggleToolExpansion,
            onToggleThinkingExpansion = onToggleThinkingExpansion,
            onToggleDiffExpansion = onToggleDiffExpansion,
            onToggleToolArgumentsExpansion = onToggleToolArgumentsExpansion,
            onPreviewImage = { uri ->
                previewImageUri = uri
            },
        )

        AnimatedVisibility(
            visible = autoScrollUi.shouldShowJumpToLatest,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) {
            OutlinedButton(
                onClick = autoScrollUi.onJumpToLatest,
                modifier = Modifier.testTag(CHAT_JUMP_TO_LATEST_TAG),
            ) {
                Text("Jump to latest")
            }
        }

        previewImageUri?.let { uri ->
            ImagePreviewDialog(
                uriString = uri,
                onDismiss = { previewImageUri = null },
            )
        }
    }
}

private data class TimelineAutoScrollUi(
    val shouldShowJumpToLatest: Boolean,
    val onJumpToLatest: () -> Unit,
)

@Suppress("LongMethod")
@Composable
private fun rememberTimelineAutoScrollUi(
    listState: androidx.compose.foundation.lazy.LazyListState,
    timeline: List<ChatTimelineItem>,
    showInlineRunProgress: Boolean,
    isRunActive: Boolean,
): TimelineAutoScrollUi {
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val contentItemsCount = timeline.size + if (showInlineRunProgress) 1 else 0
    val renderedItemsCount = contentItemsCount + 1 // includes bottom anchor item
    val latestTimelineActivityKey =
        remember(timeline, showInlineRunProgress) {
            buildLatestTimelineActivityKey(
                timeline = timeline,
                showInlineRunProgress = showInlineRunProgress,
            )
        }
    val isNearBottom = rememberIsNearBottom(listState)
    var shouldStickToBottom by
        rememberShouldStickToBottom(
            listState = listState,
            isNearBottom = isNearBottom,
            renderedItemsCount = renderedItemsCount,
        )

    val shouldAutoScrollToBottom = shouldStickToBottom || isNearBottom

    RunActivityAutoScroll(
        listState = listState,
        latestTimelineActivityKey = latestTimelineActivityKey,
        renderedItemsCount = renderedItemsCount,
        shouldAutoScrollToBottom = shouldAutoScrollToBottom,
    )

    RunStreamingAutoScroll(
        listState = listState,
        isRunActive = isRunActive,
        shouldAutoScrollToBottom = shouldAutoScrollToBottom,
        renderedItemsCount = renderedItemsCount,
    )

    return TimelineAutoScrollUi(
        shouldShowJumpToLatest = renderedItemsCount > 1 && !shouldAutoScrollToBottom,
        onJumpToLatest = {
            shouldStickToBottom = true
            coroutineScope.launch {
                listState.animateScrollToItem(renderedItemsCount - 1)
            }
        },
    )
}

private fun buildLatestTimelineActivityKey(
    timeline: List<ChatTimelineItem>,
    showInlineRunProgress: Boolean,
): String {
    val tail = timeline.lastOrNull()
    val tailKey =
        when (tail) {
            is ChatTimelineItem.Assistant -> {
                "assistant:${tail.id}:${tail.text.length}:${tail.thinking?.length ?: 0}:${tail.isStreaming}"
            }

            is ChatTimelineItem.Tool -> {
                "tool:${tail.id}:${tail.output.length}:${tail.isStreaming}:${tail.isCollapsed}"
            }

            is ChatTimelineItem.User -> "user:${tail.id}:${tail.text.length}:${tail.imageCount}"
            null -> "empty"
        }

    return "$tailKey:inline=$showInlineRunProgress:count=${timeline.size}"
}

@Composable
private fun rememberIsNearBottom(listState: androidx.compose.foundation.lazy.LazyListState): Boolean {
    val isNearBottom by
        remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                val lastItemIndex = layoutInfo.totalItemsCount - 1

                lastItemIndex <= 0 || lastVisibleIndex >= lastItemIndex - AUTO_SCROLL_BOTTOM_THRESHOLD_ITEMS
            }
        }

    return isNearBottom
}

@Composable
private fun rememberShouldStickToBottom(
    listState: androidx.compose.foundation.lazy.LazyListState,
    isNearBottom: Boolean,
    renderedItemsCount: Int,
): androidx.compose.runtime.MutableState<Boolean> {
    val shouldStickToBottom = remember { mutableStateOf(true) }

    LaunchedEffect(listState.isScrollInProgress, isNearBottom, renderedItemsCount) {
        if (renderedItemsCount <= 1) {
            shouldStickToBottom.value = true
            return@LaunchedEffect
        }

        if (listState.isScrollInProgress) {
            shouldStickToBottom.value = isNearBottom
        }
    }

    return shouldStickToBottom
}

@Composable
private fun RunActivityAutoScroll(
    listState: androidx.compose.foundation.lazy.LazyListState,
    latestTimelineActivityKey: String,
    renderedItemsCount: Int,
    shouldAutoScrollToBottom: Boolean,
) {
    var lastAutoScrollAtMs by remember { mutableStateOf(0L) }

    LaunchedEffect(latestTimelineActivityKey, renderedItemsCount, shouldAutoScrollToBottom) {
        if (renderedItemsCount <= 0 || !shouldAutoScrollToBottom) {
            return@LaunchedEffect
        }

        val targetIndex = renderedItemsCount - 1
        val now = System.currentTimeMillis()

        when {
            lastAutoScrollAtMs == 0L -> listState.scrollToItem(targetIndex)
            now - lastAutoScrollAtMs >= AUTO_SCROLL_ANIMATION_MIN_INTERVAL_MS ->
                listState.animateScrollToItem(targetIndex)

            else -> listState.scrollToItem(targetIndex)
        }

        lastAutoScrollAtMs = now
    }
}

@Composable
private fun RunStreamingAutoScroll(
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRunActive: Boolean,
    shouldAutoScrollToBottom: Boolean,
    renderedItemsCount: Int,
) {
    LaunchedEffect(
        isRunActive,
        shouldAutoScrollToBottom,
        renderedItemsCount,
        listState.isScrollInProgress,
    ) {
        val shouldRunStreamingAutoScrollLoop =
            isRunActive &&
                shouldAutoScrollToBottom &&
                renderedItemsCount > 0 &&
                !listState.isScrollInProgress
        if (!shouldRunStreamingAutoScrollLoop) {
            return@LaunchedEffect
        }

        while (true) {
            val targetIndex = renderedItemsCount - 1
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (lastVisibleIndex < targetIndex) {
                listState.scrollToItem(targetIndex)
            }
            delay(STREAMING_AUTO_SCROLL_CHECK_INTERVAL_MS)
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ChatTimelineList(
    listState: androidx.compose.foundation.lazy.LazyListState,
    timeline: List<ChatTimelineItem>,
    hasOlderMessages: Boolean,
    hiddenHistoryCount: Int,
    expandedToolArguments: Set<String>,
    showInlineRunProgress: Boolean,
    runPhase: LiveRunPhase,
    runElapsedSeconds: Long,
    onLoadOlderMessages: () -> Unit,
    onToggleToolExpansion: (String) -> Unit,
    onToggleThinkingExpansion: (String) -> Unit,
    onToggleDiffExpansion: (String) -> Unit,
    onToggleToolArgumentsExpansion: (String) -> Unit,
    onPreviewImage: (String) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasOlderMessages) {
            item(key = "load-older-messages") {
                TextButton(
                    onClick = onLoadOlderMessages,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Load older messages ($hiddenHistoryCount hidden)")
                }
            }
        }

        items(items = timeline, key = { item -> item.id }) { item ->
            ChatTimelineRow(
                item = item,
                expandedToolArguments = expandedToolArguments,
                onToggleToolExpansion = onToggleToolExpansion,
                onToggleThinkingExpansion = onToggleThinkingExpansion,
                onToggleDiffExpansion = onToggleDiffExpansion,
                onToggleToolArgumentsExpansion = onToggleToolArgumentsExpansion,
                onPreviewImage = onPreviewImage,
            )
        }

        if (showInlineRunProgress) {
            item(key = "inline-run-progress") {
                InlineRunProgressCard(
                    phase = runPhase,
                    elapsedSeconds = runElapsedSeconds,
                )
            }
        }

        item(key = CHAT_TIMELINE_BOTTOM_ANCHOR_KEY) {
            Spacer(modifier = Modifier.height(1.dp))
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ChatTimelineRow(
    item: ChatTimelineItem,
    expandedToolArguments: Set<String>,
    onToggleToolExpansion: (String) -> Unit,
    onToggleThinkingExpansion: (String) -> Unit,
    onToggleDiffExpansion: (String) -> Unit,
    onToggleToolArgumentsExpansion: (String) -> Unit,
    onPreviewImage: (String) -> Unit,
) {
    when (item) {
        is ChatTimelineItem.User -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                UserCard(
                    text = item.text,
                    imageCount = item.imageCount,
                    imageUris = item.imageUris,
                    onImageClick = onPreviewImage,
                )
            }
        }

        is ChatTimelineItem.Assistant -> {
            AssistantCard(
                item = item,
                onToggleThinkingExpansion = onToggleThinkingExpansion,
            )
        }

        is ChatTimelineItem.Tool -> {
            ToolCard(
                item = item,
                isArgumentsExpanded = item.id in expandedToolArguments,
                onToggleToolExpansion = onToggleToolExpansion,
                onToggleDiffExpansion = onToggleDiffExpansion,
                onToggleArgumentsExpansion = onToggleToolArgumentsExpansion,
            )
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun UserCard(
    text: String,
    imageCount: Int,
    imageUris: List<String>,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.widthIn(max = 340.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "You",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = text.ifBlank { "(empty)" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            if (imageUris.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(
                        items = imageUris.take(MAX_INLINE_USER_IMAGE_PREVIEWS),
                        key = { index, uri -> "$uri-$index" },
                    ) { _, uriString ->
                        UserImagePreview(
                            uriString = uriString,
                            onClick = { onImageClick(uriString) },
                        )
                    }

                    val remaining = imageUris.size - MAX_INLINE_USER_IMAGE_PREVIEWS
                    if (remaining > 0) {
                        item(key = "more-images") {
                            Box(
                                modifier =
                                    Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = "+$remaining", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            if (imageCount > 0) {
                Text(
                    text = if (imageCount == 1) "📎 1 image attached" else "📎 $imageCount images attached",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun UserImagePreview(
    uriString: String,
    onClick: () -> Unit,
) {
    val uri = remember(uriString) { Uri.parse(uriString) }
    var loadFailed by remember(uriString) { mutableStateOf(false) }

    if (loadFailed) {
        Box(
            modifier =
                Modifier
                    .size(USER_IMAGE_PREVIEW_SIZE_DP.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "IMG",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    AsyncImage(
        model = uri,
        contentDescription = "Sent image preview",
        modifier =
            Modifier
                .size(USER_IMAGE_PREVIEW_SIZE_DP.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
        contentScale = ContentScale.Crop,
        onError = {
            loadFailed = true
        },
    )
}

@Composable
private fun AssistantCard(
    item: ChatTimelineItem.Assistant,
    onToggleThinkingExpansion: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val title = if (item.isStreaming) "Assistant (streaming)" else "Assistant"
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            AssistantMessageContent(
                text = item.text,
                modifier = Modifier.fillMaxWidth(),
            )

            ThinkingBlock(
                thinking = item.thinking,
                isThinkingComplete = item.isThinkingComplete,
                isThinkingExpanded = item.isThinkingExpanded,
                itemId = item.id,
                onToggleThinkingExpansion = onToggleThinkingExpansion,
            )
        }
    }
}

@Composable
private fun AssistantMessageContent(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) {
        Text(
            text = "(empty)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = modifier,
        )
        return
    }

    val blocks = remember(text) { parseAssistantMessageBlocks(text) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is AssistantMessageBlock.Paragraph -> {
                    if (block.text.isNotBlank()) {
                        MarkdownText(
                            markdown = block.text,
                            style = MaterialTheme.typography.bodyMedium,
                            syntaxHighlightColor = MaterialTheme.colorScheme.surfaceVariant,
                            syntaxHighlightTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is AssistantMessageBlock.Code -> {
                    AssistantCodeBlock(
                        code = block.code,
                        language = block.language,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantCodeBlock(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val highlighted = highlightCodeBlock(code, language, colors)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
    ) {
        SelectionContainer {
            Text(
                text = highlighted,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun ThinkingHeader(isThinkingComplete: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Menu,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
            text = if (isThinkingComplete) " Thinking" else " Thinking…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun ThinkingBlock(
    thinking: String?,
    isThinkingComplete: Boolean,
    isThinkingExpanded: Boolean,
    itemId: String,
    onToggleThinkingExpansion: (String) -> Unit,
) {
    if (thinking == null) return

    val thinkingStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onTertiaryContainer)
    val shouldCollapse = thinking.length > THINKING_COLLAPSE_THRESHOLD
    val displayThinking =
        if (!isThinkingExpanded && shouldCollapse) {
            thinking.take(THINKING_COLLAPSE_THRESHOLD) + "…"
        } else {
            thinking
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
            ),
        border =
            androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ThinkingHeader(isThinkingComplete)
            MarkdownText(
                markdown = displayThinking,
                style = thinkingStyle,
                syntaxHighlightColor = MaterialTheme.colorScheme.tertiaryContainer,
                syntaxHighlightTextColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )

            if (shouldCollapse || isThinkingExpanded) {
                TextButton(
                    onClick = { onToggleThinkingExpansion(itemId) },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        if (isThinkingExpanded) "Show less" else "Show more",
                    )
                }
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun ToolCard(
    item: ChatTimelineItem.Tool,
    isArgumentsExpanded: Boolean,
    onToggleToolExpansion: (String) -> Unit,
    onToggleDiffExpansion: (String) -> Unit,
    onToggleArgumentsExpansion: (String) -> Unit,
) {
    val isEditTool = item.toolName == "edit" && item.editDiff != null
    val toolInfo = getToolInfo(item.toolName)
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Tool header with icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Tool icon with color
                Box(
                    modifier =
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(toolInfo.color.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = toolInfo.icon,
                        contentDescription = item.toolName,
                        tint = toolInfo.color,
                        modifier = Modifier.size(18.dp),
                    )
                }

                val suffix =
                    when {
                        item.isError -> "(error)"
                        item.isStreaming -> "(running)"
                        else -> ""
                    }

                Text(
                    text = "${item.toolName} $suffix".trim(),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )

                if (item.isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            // Collapsible arguments section
            if (item.arguments.isNotEmpty()) {
                ToolArgumentsSection(
                    arguments = item.arguments,
                    isExpanded = isArgumentsExpanded,
                    onToggleExpand = { onToggleArgumentsExpansion(item.id) },
                    onCopy = {
                        val argsJson = item.arguments.entries.joinToString("\n") { (k, v) -> "\"$k\": \"$v\"" }
                        clipboardManager.setText(AnnotatedString("{\n$argsJson\n}"))
                    },
                )
            }

            // Show diff viewer for edit tools, otherwise show standard output
            if (isEditTool && item.editDiff != null) {
                DiffViewer(
                    diffInfo = item.editDiff,
                    isCollapsed = !item.isDiffExpanded,
                    onToggleCollapse = { onToggleDiffExpansion(item.id) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                val displayOutput =
                    if (item.isCollapsed && item.output.length > COLLAPSED_OUTPUT_LENGTH) {
                        item.output.take(COLLAPSED_OUTPUT_LENGTH) + "…"
                    } else {
                        item.output
                    }

                val rawOutput = displayOutput.ifBlank { "(no output yet)" }
                val shouldHighlight = !item.isStreaming && rawOutput.length <= TOOL_HIGHLIGHT_MAX_LENGTH

                SelectionContainer {
                    if (shouldHighlight) {
                        val inferredLanguage = inferLanguageFromToolContext(item)
                        val highlightedOutput =
                            highlightCodeBlock(
                                code = rawOutput,
                                language = inferredLanguage,
                                colors = MaterialTheme.colorScheme,
                            )
                        Text(
                            text = highlightedOutput,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    } else {
                        Text(
                            text = rawOutput,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                if (item.output.length > COLLAPSED_OUTPUT_LENGTH) {
                    TextButton(onClick = { onToggleToolExpansion(item.id) }) {
                        Icon(
                            imageVector = if (item.isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(if (item.isCollapsed) "Expand" else "Collapse")
                    }
                }
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun ToolArgumentsSection(
    arguments: Map<String, String>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopy: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.clickable { onToggleExpand() },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Arguments (${arguments.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy arguments",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (isExpanded) {
            SelectionContainer {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    arguments.forEach { (key, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = "=",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val displayValue =
                                if (value.length > MAX_ARG_DISPLAY_LENGTH) {
                                    value.take(MAX_ARG_DISPLAY_LENGTH) + "…"
                                } else {
                                    value
                                }
                            Text(
                                text = displayValue,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Get tool icon and color based on tool name.
 */
@Composable
private fun getToolInfo(toolName: String): ToolDisplayInfo {
    val colors = MaterialTheme.colorScheme
    return when (toolName) {
        "read" -> ToolDisplayInfo(Icons.Default.Description, colors.primary)
        "write" -> ToolDisplayInfo(Icons.Default.Edit, colors.secondary)
        "edit" -> ToolDisplayInfo(Icons.Default.Edit, colors.tertiary)
        "bash" -> ToolDisplayInfo(Icons.Default.Terminal, colors.error)
        "grep", "rg", "find" -> ToolDisplayInfo(Icons.Default.Search, colors.primary)
        "ls" -> ToolDisplayInfo(Icons.Default.Folder, colors.secondary)
        else -> ToolDisplayInfo(Icons.Default.Terminal, colors.outline)
    }
}

private data class ToolDisplayInfo(
    val icon: ImageVector,
    val color: Color,
)

private fun inferLanguageFromToolContext(item: ChatTimelineItem.Tool): String? {
    val path = item.arguments["path"] ?: return null
    val extension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return TOOL_OUTPUT_LANGUAGE_BY_EXTENSION[extension]
}
