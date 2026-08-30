package com.ayagmar.pimobile.ui.chat

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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ayagmar.pimobile.chat.ChatImageSource
import com.ayagmar.pimobile.chat.ChatTimelineItem
import com.ayagmar.pimobile.chat.ChatTurn
import com.ayagmar.pimobile.chat.ChatTurnSection
import com.ayagmar.pimobile.chat.projectChatTurns
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
            text = "暂无聊天消息。请恢复一个会话并发送消息。",
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

@Suppress("LongMethod", "LongParameterList")
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
    var previewImage by remember { mutableStateOf<ChatImageSource?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val turns = remember(timeline) { projectChatTurns(timeline) }
    var prependAnchor by remember { mutableStateOf<TimelinePrependAnchor?>(null) }

    LaunchedEffect(turns, timeline.size, hasOlderMessages, prependAnchor) {
        val anchor = prependAnchor ?: return@LaunchedEffect
        val timelineGrew = timeline.size > anchor.timelineItemCount
        val loadOlderAvailabilityChanged = hasOlderMessages != anchor.hadOlderMessages
        if (!timelineGrew && !loadOlderAvailabilityChanged) return@LaunchedEffect
        if (timelineGrew) {
            val turnIndex = turns.indexOfFirst { turn -> turn.containsItem(anchor.itemId) }
            if (turnIndex >= 0) {
                val loadOlderOffset = if (hasOlderMessages) 1 else 0
                listState.scrollToItem(turnIndex + loadOlderOffset, anchor.scrollOffset)
            }
        }
        prependAnchor = null
    }

    val autoScrollUi =
        rememberTimelineAutoScrollUi(
            listState = listState,
            timeline = timeline,
            hasOlderMessages = hasOlderMessages,
            showInlineRunProgress = showInlineRunProgress,
            isRunActive = isRunActive,
            renderedTimelineSize = turns.size,
            isPreservingPrepend = prependAnchor != null,
        )

    Box(modifier = modifier.fillMaxWidth()) {
        ChatTimelineList(
            listState = listState,
            turns = turns,
            hasOlderMessages = hasOlderMessages,
            hiddenHistoryCount = hiddenHistoryCount,
            expandedToolArguments = expandedToolArguments,
            showInlineRunProgress = showInlineRunProgress,
            runPhase = runPhase,
            runElapsedSeconds = runElapsedSeconds,
            onLoadOlderMessages = {
                prependAnchor =
                    listState.capturePrependAnchor(
                        turns = turns,
                        timelineItemCount = timeline.size,
                        hasOlderMessages = hasOlderMessages,
                    )
                onLoadOlderMessages()
            },
            onToggleToolExpansion = { itemId ->
                autoScrollUi.onDisclosureInteraction()
                onToggleToolExpansion(itemId)
            },
            onToggleThinkingExpansion = { itemId ->
                autoScrollUi.onDisclosureInteraction()
                onToggleThinkingExpansion(itemId)
            },
            onToggleDiffExpansion = { itemId ->
                autoScrollUi.onDisclosureInteraction()
                onToggleDiffExpansion(itemId)
            },
            onToggleToolArgumentsExpansion = { itemId ->
                autoScrollUi.onDisclosureInteraction()
                onToggleToolArgumentsExpansion(itemId)
            },
            onDisclosureInteraction = autoScrollUi.onDisclosureInteraction,
            onPreviewImage = { image ->
                autoScrollUi.onDisclosureInteraction()
                previewImage = image
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
                Text("已暂停跟随 · ↓ ${autoScrollUi.unreadLabel}")
            }
        }

        AnimatedVisibility(
            visible = autoScrollUi.isFollowingLive && isRunActive,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 2.dp,
            ) {
                Text(
                    text = "正在跟随最新消息",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        previewImage?.let { image ->
            ImagePreviewDialog(
                image = image,
                onDismiss = { previewImage = null },
            )
        }
    }
}

private data class TimelinePrependAnchor(
    val itemId: String,
    val scrollOffset: Int,
    val timelineItemCount: Int,
    val hadOlderMessages: Boolean,
)

private fun ChatTurn.containsItem(itemId: String): Boolean =
    user?.id == itemId || activity.any { item -> item.id == itemId }

private fun androidx.compose.foundation.lazy.LazyListState.capturePrependAnchor(
    turns: List<ChatTurn>,
    timelineItemCount: Int,
    hasOlderMessages: Boolean,
): TimelinePrependAnchor? {
    val turnsByKey = turns.associateBy(ChatTurn::key)
    val visibleTurn =
        layoutInfo.visibleItemsInfo
            .mapNotNull { visibleItem ->
                val turn = turnsByKey[visibleItem.key]
                if (turn == null) null else turn to visibleItem.offset
            }.firstOrNull()
    return visibleTurn?.let { (turn, scrollOffset) ->
        val itemId = turn.user?.id ?: turn.activity.firstOrNull()?.id
        itemId?.let {
            TimelinePrependAnchor(
                itemId = it,
                scrollOffset = scrollOffset,
                timelineItemCount = timelineItemCount,
                hadOlderMessages = hasOlderMessages,
            )
        }
    }
}

private data class TimelineAutoScrollUi(
    val shouldShowJumpToLatest: Boolean,
    val isFollowingLive: Boolean,
    val unreadLabel: String,
    val onDisclosureInteraction: () -> Unit,
    val onJumpToLatest: () -> Unit,
)

@Suppress("LongMethod", "LongParameterList")
@Composable
private fun rememberTimelineAutoScrollUi(
    listState: androidx.compose.foundation.lazy.LazyListState,
    timeline: List<ChatTimelineItem>,
    hasOlderMessages: Boolean,
    showInlineRunProgress: Boolean,
    isRunActive: Boolean,
    renderedTimelineSize: Int,
    isPreservingPrepend: Boolean,
): TimelineAutoScrollUi {
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val bottomAnchorIndex = timelineBottomAnchorIndex(renderedTimelineSize, hasOlderMessages, showInlineRunProgress)
    val renderedItemsCount = bottomAnchorIndex + 1
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

    var readingState by
        rememberSaveable(
            stateSaver =
                androidx.compose.runtime.saveable.listSaver<TimelineReadingState, Any>(
                    save = {
                        listOf(
                            it.sticksToBottom,
                            it.assistantUnreadCount,
                            it.toolUnreadCount,
                        )
                    },
                    restore = {
                        TimelineReadingState(
                            sticksToBottom = it[0] as Boolean,
                            assistantUnreadCount = it[1] as Int,
                            toolUnreadCount = it[2] as Int,
                        )
                    },
                ),
        ) { mutableStateOf(TimelineReadingState()) }
    val shouldAutoScrollToBottom =
        readingState.sticksToBottom &&
            (shouldStickToBottom || isNearBottom) &&
            !isPreservingPrepend
    val activityIdentities = remember(timeline) { timelineActivityIdentities(timeline) }
    var previousActivityIdentities by remember { mutableStateOf(activityIdentities) }

    LaunchedEffect(isNearBottom, listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) return@LaunchedEffect
        val action =
            if (isNearBottom) {
                TimelineReadingAction.ReachBottom
            } else {
                TimelineReadingAction.ScrollAway
            }
        readingState = reduceTimelineReadingState(readingState, action)
    }
    LaunchedEffect(activityIdentities) {
        val unreadDelta = countNewTimelineActivities(previousActivityIdentities, activityIdentities)
        if (!shouldAutoScrollToBottom && !isPreservingPrepend) {
            readingState =
                reduceTimelineReadingState(
                    readingState,
                    TimelineReadingAction.NewActivity(unreadDelta),
                )
        }
        previousActivityIdentities = activityIdentities
    }

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
        isFollowingLive = shouldAutoScrollToBottom,
        unreadLabel = formatUnreadActivityLabel(readingState),
        onDisclosureInteraction = {
            shouldStickToBottom = false
            readingState =
                reduceTimelineReadingState(
                    readingState,
                    TimelineReadingAction.DisclosureChanged,
                )
        },
        onJumpToLatest = {
            shouldStickToBottom = true
            readingState = reduceTimelineReadingState(readingState, TimelineReadingAction.JumpToLatest)
            coroutineScope.launch {
                listState.scrollToItem(bottomAnchorIndex)
            }
        },
    )
}

internal fun timelineBottomAnchorIndex(
    timelineSize: Int,
    hasOlderMessages: Boolean,
    showInlineRunProgress: Boolean,
): Int =
    timelineSize +
        (if (hasOlderMessages) 1 else 0) +
        (if (showInlineRunProgress) 1 else 0)

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
                "tool:${tail.id}:${tail.output.length}:${tail.isStreaming}:${tail.isError}"
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
    val shouldStickToBottom = rememberSaveable { mutableStateOf(true) }

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
    var lastAutoScrollAtMs by remember { mutableLongStateOf(0L) }

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
    turns: List<ChatTurn>,
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
    onDisclosureInteraction: () -> Unit,
    onPreviewImage: (ChatImageSource) -> Unit,
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
                    Text("加载更早的消息（已隐藏 $hiddenHistoryCount 条）")
                }
            }
        }

        items(items = turns, key = { turn -> turn.key }) { turn ->
            ChatTurnRow(
                turn = turn,
                expandedToolArguments = expandedToolArguments,
                onToggleToolExpansion = onToggleToolExpansion,
                onToggleThinkingExpansion = onToggleThinkingExpansion,
                onToggleDiffExpansion = onToggleDiffExpansion,
                onToggleToolArgumentsExpansion = onToggleToolArgumentsExpansion,
                onDisclosureInteraction = onDisclosureInteraction,
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
private fun ChatTurnRow(
    turn: ChatTurn,
    expandedToolArguments: Set<String>,
    onToggleToolExpansion: (String) -> Unit,
    onToggleThinkingExpansion: (String) -> Unit,
    onToggleDiffExpansion: (String) -> Unit,
    onToggleToolArgumentsExpansion: (String) -> Unit,
    onDisclosureInteraction: () -> Unit,
    onPreviewImage: (ChatImageSource) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        turn.user?.let { user ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                UserCard(
                    text = user.text,
                    imageCount = user.imageCount,
                    images = user.images,
                    onImageClick = onPreviewImage,
                )
            }
        }

        turn.sections.forEach { section ->
            when (section) {
                is ChatTurnSection.Assistant ->
                    AssistantCard(
                        item = section.item,
                        onToggleThinkingExpansion = onToggleThinkingExpansion,
                    )
                is ChatTurnSection.Tools ->
                    ToolActivityGroup(
                        sectionKey = section.key,
                        tools = section.items,
                        expandedToolArguments = expandedToolArguments,
                        onToggleToolExpansion = onToggleToolExpansion,
                        onToggleDiffExpansion = onToggleDiffExpansion,
                        onToggleToolArgumentsExpansion = onToggleToolArgumentsExpansion,
                        onDisclosureInteraction = onDisclosureInteraction,
                    )
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ToolActivityGroup(
    sectionKey: String,
    tools: List<ChatTimelineItem.Tool>,
    expandedToolArguments: Set<String>,
    onToggleToolExpansion: (String) -> Unit,
    onToggleDiffExpansion: (String) -> Unit,
    onToggleToolArgumentsExpansion: (String) -> Unit,
    onDisclosureInteraction: () -> Unit,
) {
    val isStreaming = tools.any { it.isStreaming }
    val hasError = tools.any { it.isError }
    var expanded by rememberSaveable(sectionKey) { mutableStateOf(isStreaming || hasError) }
    var wasStreaming by remember(sectionKey) { mutableStateOf(isStreaming) }

    LaunchedEffect(isStreaming) {
        if (shouldCollapseSettledToolGroup(wasStreaming, isStreaming, hasError)) {
            expanded = false
        }
        wasStreaming = isStreaming
    }

    ToolGroupDisclosure(
        tools = tools,
        expanded = expanded,
        onToggle = {
            onDisclosureInteraction()
            expanded = !expanded
        },
    )
    if (expanded) {
        tools.forEach { tool ->
            ToolActivityRow(
                item = tool,
                isArgumentsExpanded = tool.id in expandedToolArguments,
                onToggleToolExpansion = onToggleToolExpansion,
                onToggleDiffExpansion = onToggleDiffExpansion,
                onToggleArgumentsExpansion = onToggleToolArgumentsExpansion,
            )
        }
    }
}

@Composable
private fun ToolGroupDisclosure(
    tools: List<ChatTimelineItem.Tool>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val running = tools.count { it.isStreaming }
    val errors = tools.count { it.isError }
    val state =
        when {
            errors > 0 -> "$errors 个失败"
            running > 0 -> "$running 个运行中"
            else -> "已完成"
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    stateDescription = if (expanded) "已展开，$state" else "已收起，$state"
                }.clickable(onClick = onToggle)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "收起工具活动" else "展开工具活动",
            modifier = Modifier.size(18.dp),
        )
        Text("使用了 ${tools.size} 个工具 · $state", style = MaterialTheme.typography.labelMedium)
    }
}

@Suppress("LongParameterList")
@Composable
private fun ToolActivityRow(
    item: ChatTimelineItem.Tool,
    isArgumentsExpanded: Boolean,
    onToggleToolExpansion: (String) -> Unit,
    onToggleDiffExpansion: (String) -> Unit,
    onToggleArgumentsExpansion: (String) -> Unit,
) {
    val presentation = remember(item) { presentToolActivity(item) }
    val statusIcon =
        when (presentation.status) {
            ToolActivityStatus.RUNNING -> Icons.Default.Terminal
            ToolActivityStatus.SUCCESS -> Icons.Default.CheckCircle
            ToolActivityStatus.ERROR -> Icons.Default.Error
        }
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        stateDescription =
                            when (presentation.status) {
                                ToolActivityStatus.RUNNING -> "运行中"
                                ToolActivityStatus.SUCCESS -> "已完成"
                                ToolActivityStatus.ERROR -> "失败"
                            }
                    }.clickable(enabled = presentation.hasDetails) { onToggleToolExpansion(item.id) }
                    .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(statusIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(presentation.summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (item.isStreaming) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            if (presentation.hasDetails) {
                Icon(
                    if (item.isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = if (item.isCollapsed) "显示详情" else "隐藏详情",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (!item.isCollapsed || item.isError) {
            ToolCard(
                item = item,
                isArgumentsExpanded = isArgumentsExpanded,
                onToggleToolExpansion = onToggleToolExpansion,
                onToggleDiffExpansion = onToggleDiffExpansion,
                onToggleArgumentsExpansion = onToggleArgumentsExpansion,
            )
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun UserCard(
    text: String,
    imageCount: Int,
    images: List<ChatImageSource>,
    onImageClick: (ChatImageSource) -> Unit,
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
            if (text.isNotBlank()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            if (images.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(
                        items = images.take(MAX_INLINE_USER_IMAGE_PREVIEWS),
                        key = { index, _ -> "user-image-$index" },
                    ) { _, image ->
                        UserImagePreview(
                            image = image,
                            onClick = { onImageClick(image) },
                        )
                    }

                    val remaining = images.size - MAX_INLINE_USER_IMAGE_PREVIEWS
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "已附加 $imageCount 张图片",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun UserImagePreview(
    image: ChatImageSource,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val presentation by rememberChatImagePresentation(context, image)
    var loadFailed by remember(image) { mutableStateOf(false) }

    if (presentation.isLoading) {
        Box(
            modifier =
                Modifier
                    .size(USER_IMAGE_PREVIEW_SIZE_DP.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        return
    }

    if (loadFailed || presentation.errorMessage != null) {
        Box(
            modifier =
                Modifier
                    .size(USER_IMAGE_PREVIEW_SIZE_DP.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "图片",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    AsyncImage(
        model = presentation.model,
        contentDescription =
            presentation.displayName?.let { "预览附加图片 $it" }
                ?: "预览附加图片",
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
    val copyToClipboard = rememberClipboardCopy()
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (item.isStreaming) "Pi · 正在回复" else "Pi",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (item.text.isNotBlank() && !item.isStreaming) {
                IconButton(
                    onClick = { copyToClipboard(item.text) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制回复",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        ThinkingBlock(
            thinking = item.thinking,
            isThinkingComplete = item.isThinkingComplete,
            isThinkingExpanded = item.isThinkingExpanded,
            itemId = item.id,
            onToggleThinkingExpansion = onToggleThinkingExpansion,
        )
        if (item.text.isNotBlank()) {
            AssistantMessageContent(
                text = item.text,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AssistantMessageContent(
    text: String,
    modifier: Modifier = Modifier,
) {
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
            text = if (isThinkingComplete) " 思考过程" else " 正在思考…",
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

    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        stateDescription =
                            if (isThinkingExpanded) {
                                "已展开"
                            } else {
                                "已收起"
                            }
                    }.clickable { onToggleThinkingExpansion(itemId) }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThinkingHeader(isThinkingComplete)
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                if (isThinkingExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isThinkingExpanded) "隐藏思考过程" else "显示思考过程",
                modifier = Modifier.size(18.dp),
            )
        }
        if (isThinkingExpanded) {
            MarkdownText(
                markdown = thinking,
                style = MaterialTheme.typography.bodySmall,
                syntaxHighlightColor = MaterialTheme.colorScheme.surfaceVariant,
                syntaxHighlightTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    val copyToClipboard = rememberClipboardCopy()

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
                        item.isError -> "（错误）"
                        item.isStreaming -> "（运行中）"
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
                } else if (item.output.isNotBlank()) {
                    IconButton(onClick = { copyToClipboard(item.output) }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制工具输出",
                            modifier = Modifier.size(18.dp),
                        )
                    }
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
                        copyToClipboard("{\n$argsJson\n}")
                    },
                )
            }

            // Show diff viewer for edit tools, otherwise show standard output
            if (isEditTool) {
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

                val rawOutput = displayOutput.ifBlank { "（暂无输出）" }
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
                        Text(if (item.isCollapsed) "展开" else "收起")
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
                    contentDescription = if (isExpanded) "收起" else "展开",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "参数（${arguments.size}）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "复制参数",
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
    val trimmedOutput = item.output.trim()
    val looksLikeJsonObject = trimmedOutput.startsWith("{") && trimmedOutput.endsWith("}")
    val looksLikeJsonArray = trimmedOutput.startsWith("[") && trimmedOutput.endsWith("]")
    val outputLanguage = if (looksLikeJsonObject || looksLikeJsonArray) "json" else null
    val path = item.arguments["path"] ?: item.arguments["file_path"]
    val pathLanguage =
        path?.let {
            val extension = it.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            TOOL_OUTPUT_LANGUAGE_BY_EXTENSION[extension]
        }
    return outputLanguage ?: pathLanguage
}
