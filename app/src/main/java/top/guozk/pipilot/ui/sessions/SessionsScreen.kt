@file:Suppress("LongMethod", "MaxLineLength", "ktlint:standard:max-line-length")

package top.guozk.pipilot.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import top.guozk.pipilot.coresessions.SessionIndexRepository
import top.guozk.pipilot.hosts.HostProfileStore
import top.guozk.pipilot.hosts.HostTokenStore
import top.guozk.pipilot.sessions.BridgeSessionShareRemoteDataSource
import top.guozk.pipilot.sessions.CwdSessionGroupUiState
import top.guozk.pipilot.sessions.HostSessionStatus
import top.guozk.pipilot.sessions.QuickReplyDeliveryMode
import top.guozk.pipilot.sessions.QuickReplyPhase
import top.guozk.pipilot.sessions.QuickReplyState
import top.guozk.pipilot.sessions.SessionAction
import top.guozk.pipilot.sessions.SessionCardDensity
import top.guozk.pipilot.sessions.SessionCockpitItem
import top.guozk.pipilot.sessions.SessionController
import top.guozk.pipilot.sessions.SessionCwdPreferenceStore
import top.guozk.pipilot.sessions.SessionFreshnessFilter
import top.guozk.pipilot.sessions.SessionSavedStateStore
import top.guozk.pipilot.sessions.SessionsUiState
import top.guozk.pipilot.sessions.SessionsViewModel
import top.guozk.pipilot.sessions.SessionsViewModelFactory
import top.guozk.pipilot.sessions.formatCwdTail
import top.guozk.pipilot.sessions.privacySafeText
import top.guozk.pipilot.ui.components.PiButton
import top.guozk.pipilot.ui.components.PiCard
import top.guozk.pipilot.ui.components.PiSpacing
import top.guozk.pipilot.ui.components.PiTextField
import top.guozk.pipilot.ui.components.PiTopBar
import java.time.Duration
import java.time.Instant
import androidx.compose.foundation.lazy.grid.items as gridItems

@Suppress("LongParameterList")
@Composable
fun SessionsRoute(
    profileStore: HostProfileStore,
    tokenStore: HostTokenStore,
    repository: SessionIndexRepository,
    sessionController: SessionController,
    cwdPreferenceStore: SessionCwdPreferenceStore,
    savedStateStore: SessionSavedStateStore,
    shareRemoteDataSource: BridgeSessionShareRemoteDataSource? = null,
    onNavigateToChat: () -> Unit = {},
) {
    val factory =
        remember(profileStore, tokenStore, repository, sessionController, cwdPreferenceStore, savedStateStore) {
            SessionsViewModelFactory(
                profileStore,
                tokenStore,
                repository,
                sessionController,
                cwdPreferenceStore,
                savedStateStore,
                shareRemoteDataSource,
            )
        }
    val model: SessionsViewModel = viewModel(factory = factory)
    val state by model.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var transientMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { model.refreshHosts() }
    LaunchedEffect(model) { model.navigateToChat.collect { onNavigateToChat() } }
    LaunchedEffect(model) { model.messages.collect { transientMessage = it } }
    LaunchedEffect(transientMessage) {
        if (transientMessage != null) {
            delay(STATUS_MESSAGE_DURATION_MS)
            transientMessage = null
        }
    }

    SessionsScreen(
        state = state,
        statusMessage = transientMessage,
        callbacks =
            SessionsCallbacks(
                onShowAllHosts = model::showAllHosts,
                onHostSelected = model::onHostSelected,
                onSearchChanged = model::onSearchQueryChanged,
                onWorkspaceSelected = model::onWorkspaceFilterChanged,
                onTogglePinned = model::togglePinnedFilter,
                onToggleHidden = model::toggleHiddenFilter,
                onToggleActive = model::toggleActiveFilter,
                onFreshnessSelected = model::setFreshnessFilter,
                onToggleDensity = model::toggleDensity,
                onRefresh = model::refreshSessions,
                onNew = model::newSession,
                onOpen = model::resumeSession,
                onPin = model::togglePin,
                onHide = model::toggleHidden,
                onRemove = model::removeSavedItem,
                onRetry = model::retrySavedItem,
                onQuickReply = model::openQuickReply,
                onShare = model::shareSession,
                onRevoke = model::revokeSessionShare,
                onRename = { model.runSessionAction(SessionAction.Rename(it)) },
                onFork = model::requestForkMessages,
                onExport = { model.runSessionAction(SessionAction.Export) },
                onCompact = { model.runSessionAction(SessionAction.Compact) },
                onForkSelected = model::forkFromSelectedMessage,
                onDismissFork = model::dismissForkPicker,
                onQuickDraft = model::updateQuickReplyDraft,
                onQuickMode = model::selectQuickReplyMode,
                onQuickSend = model::sendQuickReply,
                onQuickDismiss = model::dismissQuickReply,
                onQuickOpenChat = model::openQuickReplyChat,
                onCopyLink = { clipboard.setText(AnnotatedString(it)) },
            ),
    )
}

@Suppress("LongParameterList")
internal data class SessionsCallbacks(
    val onShowAllHosts: () -> Unit = {},
    val onHostSelected: (String) -> Unit = {},
    val onSearchChanged: (String) -> Unit = {},
    val onWorkspaceSelected: (String?) -> Unit = {},
    val onTogglePinned: () -> Unit = {},
    val onToggleHidden: () -> Unit = {},
    val onToggleActive: () -> Unit = {},
    val onFreshnessSelected: (SessionFreshnessFilter) -> Unit = {},
    val onToggleDensity: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onNew: () -> Unit = {},
    val onOpen: (SessionCockpitItem) -> Unit = {},
    val onPin: (SessionCockpitItem) -> Unit = {},
    val onHide: (SessionCockpitItem) -> Unit = {},
    val onRemove: (SessionCockpitItem) -> Unit = {},
    val onRetry: (SessionCockpitItem) -> Unit = {},
    val onQuickReply: (SessionCockpitItem) -> Unit = {},
    val onShare: (SessionCockpitItem) -> Unit = {},
    val onRevoke: (SessionCockpitItem) -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onFork: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onCompact: () -> Unit = {},
    val onForkSelected: (String) -> Unit = {},
    val onDismissFork: () -> Unit = {},
    val onQuickDraft: (String) -> Unit = {},
    val onQuickMode: (QuickReplyDeliveryMode) -> Unit = {},
    val onQuickSend: (Boolean) -> Unit = {},
    val onQuickDismiss: () -> Unit = {},
    val onQuickOpenChat: () -> Unit = {},
    val onCopyLink: (String) -> Unit = {},
)

@Composable
@Suppress("LongMethod")
internal fun SessionsScreen(
    state: SessionsUiState,
    statusMessage: String?,
    callbacks: SessionsCallbacks,
) {
    var renameDraft by rememberSaveable { mutableStateOf("") }
    var showRename by rememberSaveable { mutableStateOf(false) }
    val activeItem = state.items.firstOrNull { it.isActive }

    Column(
        modifier = Modifier.fillMaxSize().padding(PiSpacing.md),
        verticalArrangement = Arrangement.spacedBy(PiSpacing.sm),
    ) {
        PiTopBar(
            title = {
                Column {
                    Text("会话控制台", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        activeItem?.let { "活动会话 · ${it.title} · ${it.hostLabel}" } ?: "从各主机中选择工作会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            actions = {
                TextButton(onClick = callbacks.onToggleDensity) {
                    Text(if (state.density == SessionCardDensity.COMPACT) "舒适" else "紧凑")
                }
                TextButton(onClick = callbacks.onRefresh, enabled = !state.isRefreshing) {
                    Text(if (state.isRefreshing) "刷新中" else "刷新")
                }
                PiButton(label = "新建", onClick = callbacks.onNew)
            },
        )

        HostFilters(state, callbacks)
        PiTextField(
            value = state.query,
            onValueChange = callbacks.onSearchChanged,
            label = "搜索会话",
        )
        CockpitFilters(state, callbacks)
        HostStatusRow(state.hostStatuses)
        StatusMessages(state.errorMessage, statusMessage, state.shareLink, callbacks.onCopyLink)

        when {
            state.hosts.isEmpty() -> Text("尚未配置主机。")
            state.isLoading && state.items.isEmpty() -> CircularProgressIndicator()
            state.items.isEmpty() ->
                Text(
                    if (state.filter.hiddenOnly) "没有已隐藏的会话。" else "没有符合当前筛选条件的会话。",
                )
            else ->
                SessionCards(state, callbacks, onRename = {
                    renameDraft = activeItem?.title.orEmpty()
                    showRename = true
                })
        }
    }

    if (showRename) {
        RenameSessionDialog(
            currentSession = activeItem?.record,
            name = renameDraft,
            isBusy = state.isPerformingAction,
            onNameChange = { renameDraft = it },
            onDismiss = { showRename = false },
            onConfirm = {
                callbacks.onRename(renameDraft)
                showRename = false
            },
        )
    }
    if (state.isForkPickerVisible) {
        ForkPickerDialog(
            state.isLoadingForkMessages,
            state.forkCandidates,
            callbacks.onDismissFork,
            callbacks.onForkSelected,
        )
    }
    QuickReplySheet(state.quickReply, state.isRunActive, state.isRetrying, callbacks)
}

@Composable
private fun HostFilters(
    state: SessionsUiState,
    callbacks: SessionsCallbacks,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = state.filter.hostId == null,
                onClick = callbacks.onShowAllHosts,
                label = { Text("全部主机") },
            )
        }
        items(state.hosts, key = { it.id }) { host ->
            FilterChip(
                selected = state.filter.hostId == host.id,
                onClick = { callbacks.onHostSelected(host.id) },
                label = { Text(privacySafeText(host.name) ?: "主机") },
            )
        }
    }
}

@Composable
private fun CockpitFilters(
    state: SessionsUiState,
    callbacks: SessionsCallbacks,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(state.filter.pinnedOnly, callbacks.onTogglePinned, { Text("已置顶") }) }
        item { FilterChip(state.filter.hiddenOnly, callbacks.onToggleHidden, { Text("已隐藏") }) }
        item { FilterChip(state.filter.activeOnly, callbacks.onToggleActive, { Text("活动中") }) }
        item {
            FilterChip(
                selected = state.filter.freshness == SessionFreshnessFilter.STALE,
                onClick = {
                    callbacks.onFreshnessSelected(
                        if (state.filter.freshness == SessionFreshnessFilter.STALE) {
                            SessionFreshnessFilter.ALL
                        } else {
                            SessionFreshnessFilter.STALE
                        },
                    )
                },
                label = { Text("已过期") },
            )
        }
        item {
            FilterChip(
                selected = state.filter.freshness == SessionFreshnessFilter.ERROR,
                onClick = {
                    callbacks.onFreshnessSelected(
                        if (state.filter.freshness == SessionFreshnessFilter.ERROR) {
                            SessionFreshnessFilter.ALL
                        } else {
                            SessionFreshnessFilter.ERROR
                        },
                    )
                },
                label = { Text("主机异常") },
            )
        }
        item {
            FilterChip(
                selected = state.filter.workspaceLabel == null,
                onClick = { callbacks.onWorkspaceSelected(null) },
                label = { Text("全部工作区") },
            )
        }
        items(state.workspaceLabels, key = { it }) { label ->
            FilterChip(
                selected = state.filter.workspaceLabel == label,
                onClick = { callbacks.onWorkspaceSelected(label) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun HostStatusRow(statuses: List<HostSessionStatus>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(statuses, key = { it.hostId }) { status ->
            Text(
                text = "${status.hostLabel}：${localizedHostStatus(status.kind.name)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusMessages(
    error: String?,
    status: String?,
    shareLink: String?,
    onCopy: (String) -> Unit,
) {
    error?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    shareLink?.let { link ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("分享链接已生成", modifier = Modifier.weight(1f))
            TextButton(onClick = { onCopy(link) }) { Text("复制链接") }
        }
    }
}

@Composable
private fun SessionCards(
    state: SessionsUiState,
    callbacks: SessionsCallbacks,
    onRename: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 720.dp
        if (expanded) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItems(state.items, key = { it.listKey }) { item -> SessionCard(item, state, callbacks, onRename) }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.items, key = { it.listKey }) { item -> SessionCard(item, state, callbacks, onRename) }
            }
        }
    }
}

@Composable
private fun SessionCard(
    item: SessionCockpitItem,
    state: SessionsUiState,
    callbacks: SessionsCallbacks,
    onRename: () -> Unit,
) {
    PiCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(if (state.density == SessionCardDensity.COMPACT) 4.dp else 8.dp),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${item.hostLabel} · ${item.workspaceLabel}${if (item.isActive) " · 活动中" else ""}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.density == SessionCardDensity.COMFORTABLE) {
                item.preview?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            val metadata =
                listOfNotNull(
                    item.model,
                    item.messageCount?.let { "$it 条消息" },
                    item.updatedAt?.let(::relativeUpdatedTime),
                    item.freshness.name.lowercase(),
                ).joinToString(" · ")
            if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodySmall)
            item.stableActionDisabledReason?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.isUnavailableSavedItem) {
                    item { TextButton(onClick = { callbacks.onRetry(item) }) { Text("重试") } }
                    item { TextButton(onClick = { callbacks.onRemove(item) }) { Text("移除") } }
                } else {
                    item { TextButton(onClick = { callbacks.onOpen(item) }) { Text("打开") } }
                    item {
                        TextButton(onClick = { callbacks.onQuickReply(item) }, enabled = item.key != null) {
                            Text("快速回复")
                        }
                    }
                    item {
                        TextButton(onClick = { callbacks.onPin(item) }, enabled = item.key != null) {
                            Text(if (item.isPinned) "取消置顶" else "置顶")
                        }
                    }
                    item {
                        TextButton(onClick = { callbacks.onHide(item) }, enabled = item.key != null) {
                            Text(if (item.isHidden) "取消隐藏" else "隐藏")
                        }
                    }
                    item {
                        TextButton(onClick = { callbacks.onShare(item) }, enabled = item.key != null) { Text("分享") }
                    }
                    item {
                        TextButton(
                            onClick = { callbacks.onRevoke(item) },
                            enabled = item.key != null,
                        ) { Text("撤销") }
                    }
                }
            }
            if (item.isActive && !item.isUnavailableSavedItem) {
                SessionActionsRow(
                    isBusy = state.isPerformingAction || state.isResuming,
                    onRenameClick = onRename,
                    onForkClick = callbacks.onFork,
                    onExportClick = callbacks.onExport,
                    onCompactClick = callbacks.onCompact,
                    onShareClick = { callbacks.onShare(item) },
                    onRevokeShareClick = { callbacks.onRevoke(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickReplySheet(
    state: QuickReplyState,
    isRunActive: Boolean,
    isRetrying: Boolean,
    callbacks: SessionsCallbacks,
) {
    if (!state.isVisible) return
    ModalBottomSheet(
        onDismissRequest = callbacks.onQuickDismiss,
        modifier = Modifier.semantics { paneTitle = "快速回复" },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("快速回复", style = MaterialTheme.typography.titleLarge)
            Text(state.targetLabel, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = state.draft,
                onValueChange = callbacks.onQuickDraft,
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                label = { Text("回复内容") },
                enabled = state.phase != QuickReplyPhase.SENDING,
            )
            if (state.phase == QuickReplyPhase.EDITING && isRunActive) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.deliveryMode == QuickReplyDeliveryMode.FOLLOW_UP,
                        onClick = { callbacks.onQuickMode(QuickReplyDeliveryMode.FOLLOW_UP) },
                        label = { Text("追加消息") },
                    )
                    FilterChip(
                        selected = state.deliveryMode == QuickReplyDeliveryMode.STEER,
                        onClick = { callbacks.onQuickMode(QuickReplyDeliveryMode.STEER) },
                        enabled = !isRetrying,
                        label = { Text("调整方向") },
                    )
                }
            }
            state.message?.let {
                Text(
                    it,
                    color = if (state.phase == QuickReplyPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = callbacks.onQuickDismiss) { Text("取消") }
                when (state.phase) {
                    QuickReplyPhase.EDITING, QuickReplyPhase.ERROR -> {
                        TextButton(
                            onClick = { callbacks.onQuickSend(false) },
                            enabled = state.draft.isNotBlank(),
                        ) { Text(if (state.phase == QuickReplyPhase.ERROR) "重试" else "发送") }
                        TextButton(
                            onClick = { callbacks.onQuickSend(true) },
                            enabled = state.draft.isNotBlank(),
                        ) { Text("发送并打开") }
                    }
                    QuickReplyPhase.SENDING -> CircularProgressIndicator()
                    QuickReplyPhase.CONFLICT, QuickReplyPhase.SENT -> {
                        if (state.canOpenChat) {
                            TextButton(onClick = callbacks.onQuickOpenChat) {
                                Text(
                                    if (state.phase == QuickReplyPhase.CONFLICT) "打开当前会话" else "打开聊天",
                                )
                            }
                        }
                    }
                    QuickReplyPhase.HIDDEN -> Unit
                }
            }
        }
    }
}

@Composable
internal fun CwdChipSelector(
    groups: List<CwdSessionGroupUiState>,
    selectedCwd: String?,
    onCwdSelected: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(groups, key = { it.cwd }) { group ->
            FilterChip(
                selected = group.cwd == selectedCwd,
                onClick = { onCwdSelected(group.cwd) },
                label = { Text("${formatCwdTail(group.cwd)} (${group.sessions.size})") },
            )
        }
    }
}

internal fun relativeUpdatedTime(
    value: String,
    now: Instant = Instant.now(),
): String {
    val instant = runCatching { Instant.parse(value) }.getOrNull() ?: return "最近更新"
    val duration = Duration.between(instant, now).coerceAtLeast(Duration.ZERO)
    return when {
        duration.toMinutes() < 1 -> "刚刚更新"
        duration.toHours() < 1 -> "${duration.toMinutes()} 分钟前更新"
        duration.toDays() < 1 -> "${duration.toHours()} 小时前更新"
        else -> "${duration.toDays()} 天前更新"
    }
}

private fun localizedHostStatus(kindName: String): String =
    when (kindName) {
        "FRESH" -> "正常"
        "LOADING" -> "加载中"
        "REFRESHING" -> "刷新中"
        "STALE" -> "数据已过期"
        "AUTH_REQUIRED" -> "需要认证"
        "UNREACHABLE" -> "无法连接"
        "ERROR" -> "发生错误"
        else -> kindName.lowercase().replace('_', ' ')
    }

private const val STATUS_MESSAGE_DURATION_MS = 3_000L
