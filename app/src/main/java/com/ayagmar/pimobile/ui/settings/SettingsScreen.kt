package com.ayagmar.pimobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayagmar.pimobile.sessions.SessionController
import com.ayagmar.pimobile.sessions.TransportPreference
import com.ayagmar.pimobile.ui.components.PiButton
import com.ayagmar.pimobile.ui.components.PiCard
import com.ayagmar.pimobile.ui.components.PiSpacing
import com.ayagmar.pimobile.ui.components.PiTopBar
import com.ayagmar.pimobile.ui.theme.ThemePreference
import kotlinx.coroutines.delay

@Composable
fun SettingsRoute(sessionController: SessionController) {
    val context = LocalContext.current
    val factory =
        remember(context, sessionController) {
            SettingsViewModelFactory(
                context = context,
                sessionController = sessionController,
            )
        }
    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
    var transientStatusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settingsViewModel) {
        settingsViewModel.messages.collect { message ->
            transientStatusMessage = message
        }
    }

    LaunchedEffect(transientStatusMessage) {
        if (transientStatusMessage != null) {
            delay(STATUS_MESSAGE_DURATION_MS)
            transientStatusMessage = null
        }
    }

    SettingsScreen(
        viewModel = settingsViewModel,
        transientStatusMessage = transientStatusMessage,
    )
}

@Composable
private fun SettingsScreen(
    viewModel: SettingsViewModel,
    transientStatusMessage: String?,
) {
    val uiState = viewModel.uiState

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PiSpacing.md),
        verticalArrangement = Arrangement.spacedBy(PiSpacing.md),
    ) {
        PiTopBar(
            title = {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            actions = {},
        )

        ConnectionStatusCard(
            state = uiState,
            transientStatusMessage = transientStatusMessage,
            onPing = viewModel::pingBridge,
        )

        AgentAutomationCard(
            autoCompactionEnabled = uiState.autoCompactionEnabled,
            autoRetryEnabled = uiState.autoRetryEnabled,
            onToggleAutoCompaction = viewModel::toggleAutoCompaction,
            onToggleAutoRetry = viewModel::toggleAutoRetry,
        )

        TransportCard(
            transportPreference = uiState.transportPreference,
            effectiveTransportPreference = uiState.effectiveTransportPreference,
            transportRuntimeNote = uiState.transportRuntimeNote,
            onTransportPreferenceSelected = viewModel::setTransportPreference,
        )

        AppearanceCard(
            themePreference = uiState.themePreference,
            showExtensionStatusStrip = uiState.showExtensionStatusStrip,
            onThemePreferenceSelected = viewModel::setThemePreference,
            onToggleExtensionStatusStrip = viewModel::toggleExtensionStatusStrip,
        )

        DeliveryModesCard(
            steeringMode = uiState.steeringMode,
            followUpMode = uiState.followUpMode,
            isUpdatingSteeringMode = uiState.isUpdatingSteeringMode,
            isUpdatingFollowUpMode = uiState.isUpdatingFollowUpMode,
            onSteeringModeSelected = viewModel::setSteeringMode,
            onFollowUpModeSelected = viewModel::setFollowUpMode,
        )

        ChatHelpCard()

        AppInfoCard(
            version = uiState.appVersion,
        )
    }
}

@Composable
private fun ConnectionStatusCard(
    state: SettingsUiState,
    transientStatusMessage: String?,
    onPing: () -> Unit,
) {
    PiCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "连接",
            style = MaterialTheme.typography.titleMedium,
        )

        ConnectionStatusRow(
            connectionStatus = state.connectionStatus,
            isChecking = state.isChecking,
        )

        ConnectionMessages(
            state = state,
            transientStatusMessage = transientStatusMessage,
        )

        PiButton(
            label = "检查连接",
            onClick = onPing,
            enabled = !state.isChecking,
            modifier = Modifier.padding(top = PiSpacing.sm),
        )
    }
}

@Composable
private fun ConnectionStatusRow(
    connectionStatus: ConnectionStatus?,
    isChecking: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val statusColor =
            when (connectionStatus) {
                ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.error
                ConnectionStatus.CHECKING -> MaterialTheme.colorScheme.tertiary
                null -> MaterialTheme.colorScheme.outline
            }

        Text(
            text = "状态：${localizedConnectionStatus(connectionStatus?.name)}",
            color = statusColor,
        )

        if (isChecking) {
            CircularProgressIndicator(
                modifier = Modifier.padding(start = 8.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun ConnectionMessages(
    state: SettingsUiState,
    transientStatusMessage: String?,
) {
    state.piVersion?.let { version ->
        Text(
            text = "当前模型：$version",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    state.sessionName?.let { sessionName ->
        Text(
            text = "当前会话：$sessionName",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    state.pendingMessageCount?.let { pendingCount ->
        Text(
            text = "排队消息：$pendingCount",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    state.clientId?.let { clientId ->
        Text(
            text = "客户端 ID：$clientId",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    transientStatusMessage?.let { status ->
        Text(
            text = status,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    state.errorMessage?.let { error ->
        Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AgentAutomationCard(
    autoCompactionEnabled: Boolean,
    autoRetryEnabled: Boolean,
    onToggleAutoCompaction: () -> Unit,
    onToggleAutoRetry: () -> Unit,
) {
    PiCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "自动化",
            style = MaterialTheme.typography.titleMedium,
        )

        SettingsToggleRow(
            title = "自动压缩上下文",
            description = "接近 Token 上限时自动压缩对话",
            checked = autoCompactionEnabled,
            onToggle = onToggleAutoCompaction,
        )

        SettingsToggleRow(
            title = "出错时自动重试",
            description = "请求失败时按指数退避策略自动重试",
            checked = autoRetryEnabled,
            onToggle = onToggleAutoRetry,
        )
    }
}

@Composable
private fun TransportCard(
    transportPreference: TransportPreference,
    effectiveTransportPreference: TransportPreference,
    transportRuntimeNote: String,
    onTransportPreferenceSelected: (TransportPreference) -> Unit,
) {
    PiCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "连接路由",
            style = MaterialTheme.typography.titleMedium,
        )

        TransportPreferenceRow(
            selectedPreference = transportPreference,
            effectivePreference = effectiveTransportPreference,
            runtimeNote = transportRuntimeNote,
            onPreferenceSelected = onTransportPreferenceSelected,
        )
    }
}

@Composable
private fun AppearanceCard(
    themePreference: ThemePreference,
    showExtensionStatusStrip: Boolean,
    onThemePreferenceSelected: (ThemePreference) -> Unit,
    onToggleExtensionStatusStrip: () -> Unit,
) {
    PiCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "外观",
            style = MaterialTheme.typography.titleMedium,
        )

        ThemePreferenceRow(
            selectedPreference = themePreference,
            onPreferenceSelected = onThemePreferenceSelected,
        )

        SettingsToggleRow(
            title = "显示扩展状态栏",
            description = "在聊天中显示紧凑的扩展运行状态",
            checked = showExtensionStatusStrip,
            onToggle = onToggleExtensionStatusStrip,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun DeliveryModesCard(
    steeringMode: String,
    followUpMode: String,
    isUpdatingSteeringMode: Boolean,
    isUpdatingFollowUpMode: Boolean,
    onSteeringModeSelected: (String) -> Unit,
    onFollowUpModeSelected: (String) -> Unit,
) {
    PiCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "流式消息发送",
            style = MaterialTheme.typography.titleMedium,
        )

        ModeSelectorRow(
            title = "调整方向模式",
            description = "流式生成过程中调整方向消息的发送方式",
            selectedMode = steeringMode,
            isUpdating = isUpdatingSteeringMode,
            onModeSelected = onSteeringModeSelected,
        )

        ModeSelectorRow(
            title = "追加消息模式",
            description = "流式生成过程中追加消息的排队方式",
            selectedMode = followUpMode,
            isUpdating = isUpdatingFollowUpMode,
            onModeSelected = onFollowUpModeSelected,
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
        )
    }
}

@Composable
private fun TransportPreferenceRow(
    selectedPreference: TransportPreference,
    effectivePreference: TransportPreference,
    runtimeNote: String,
    onPreferenceSelected: (TransportPreference) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "传输方式",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "App 与 Bridge 之间优先使用的传输协议",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportOptionButton(
                label = "自动",
                selected = selectedPreference == TransportPreference.AUTO,
                onClick = { onPreferenceSelected(TransportPreference.AUTO) },
            )
            TransportOptionButton(
                label = "WebSocket",
                selected = selectedPreference == TransportPreference.WEBSOCKET,
                onClick = { onPreferenceSelected(TransportPreference.WEBSOCKET) },
            )
            TransportOptionButton(
                label = "SSE",
                selected = selectedPreference == TransportPreference.SSE,
                onClick = { onPreferenceSelected(TransportPreference.SSE) },
            )
        }

        Text(
            text = "当前使用：${localizedTransport(effectivePreference.value)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        if (runtimeNote.isNotBlank()) {
            Text(
                text = runtimeNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemePreferenceRow(
    selectedPreference: ThemePreference,
    onPreferenceSelected: (ThemePreference) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "主题",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "选择 App 外观模式",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeOptionButton(
                label = "跟随系统",
                selected = selectedPreference == ThemePreference.SYSTEM,
                onClick = { onPreferenceSelected(ThemePreference.SYSTEM) },
            )
            ThemeOptionButton(
                label = "浅色",
                selected = selectedPreference == ThemePreference.LIGHT,
                onClick = { onPreferenceSelected(ThemePreference.LIGHT) },
            )
            ThemeOptionButton(
                label = "深色",
                selected = selectedPreference == ThemePreference.DARK,
                onClick = { onPreferenceSelected(ThemePreference.DARK) },
            )
        }
    }
}

@Composable
private fun ModeSelectorRow(
    title: String,
    description: String,
    selectedMode: String,
    isUpdating: Boolean,
    onModeSelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeOptionButton(
                label = "全部",
                selected = selectedMode == SettingsViewModel.MODE_ALL,
                enabled = !isUpdating,
                onClick = { onModeSelected(SettingsViewModel.MODE_ALL) },
            )
            ModeOptionButton(
                label = "一次一条",
                selected = selectedMode == SettingsViewModel.MODE_ONE_AT_A_TIME,
                enabled = !isUpdating,
                onClick = { onModeSelected(SettingsViewModel.MODE_ONE_AT_A_TIME) },
            )
        }
    }
}

@Composable
private fun TransportOptionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    PiButton(
        label = label,
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun ThemeOptionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    PiButton(
        label = label,
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun ModeOptionButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    PiButton(
        label = label,
        selected = selected,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun ChatHelpCard() {
    PiCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "聊天操作与手势",
            style = MaterialTheme.typography.titleMedium,
        )

        HelpItem(
            action = "发送",
            help = "点击发送图标，或使用键盘的发送操作",
        )
        HelpItem(
            action = "命令",
            help = "点击输入框中的菜单图标打开斜杠命令",
        )
        HelpItem(
            action = "模型",
            help = "点击模型标签快速切换；长按打开完整模型列表",
        )
        HelpItem(
            action = "思考/工具输出",
            help = "点击展开或收起按钮查看较长内容",
        )
        HelpItem(
            action = "会话树",
            help = "从聊天标题栏打开会话树，查看分支并从条目处分叉",
        )
        HelpItem(
            action = "终端与统计",
            help = "使用聊天标题栏中的终端和图表图标",
        )
    }
}

@Composable
private fun HelpItem(
    action: String,
    help: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = action,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = help,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppInfoCard(version: String) {
    PiCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "关于",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "版本：$version",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun localizedConnectionStatus(status: String?): String =
    when (status) {
        "CONNECTED" -> "已连接"
        "CONNECTING" -> "连接中"
        "RECONNECTING" -> "重新连接中"
        "DISCONNECTED" -> "未连接"
        else -> "未知"
    }

private fun localizedTransport(transport: String): String =
    when (transport.lowercase()) {
        "auto" -> "自动"
        "websocket" -> "WebSocket"
        "sse" -> "SSE"
        else -> transport
    }

private const val STATUS_MESSAGE_DURATION_MS = 3_000L
