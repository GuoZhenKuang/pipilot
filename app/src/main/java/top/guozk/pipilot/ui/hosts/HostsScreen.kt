package top.guozk.pipilot.ui.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import top.guozk.pipilot.hosts.ConnectionDiagnostics
import top.guozk.pipilot.hosts.DiagnosticStatus
import top.guozk.pipilot.hosts.DiagnosticsResult
import top.guozk.pipilot.hosts.HostDraft
import top.guozk.pipilot.hosts.HostProfileItem
import top.guozk.pipilot.hosts.HostProfileStore
import top.guozk.pipilot.hosts.HostTokenStore
import top.guozk.pipilot.hosts.HostsUiState
import top.guozk.pipilot.hosts.HostsViewModel
import top.guozk.pipilot.hosts.HostsViewModelFactory
import top.guozk.pipilot.hosts.parseHostPairingPayload
import top.guozk.pipilot.hosts.toRecoveryMessage

@Composable
fun HostsRoute(
    profileStore: HostProfileStore,
    tokenStore: HostTokenStore,
    diagnostics: ConnectionDiagnostics,
    onHostSaved: () -> Unit = {},
) {
    val factory =
        remember(profileStore, tokenStore, diagnostics) {
            HostsViewModelFactory(
                profileStore = profileStore,
                tokenStore = tokenStore,
                diagnostics = diagnostics,
            )
        }
    val hostsViewModel: HostsViewModel = viewModel(factory = factory)
    val uiState by hostsViewModel.uiState.collectAsStateWithLifecycle()

    var editorDraft by remember { mutableStateOf<HostDraft?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val scanPairingCode =
        rememberPairingScanner { result ->
            result
                .onSuccess { draft -> editorDraft = draft }
                .onFailure { error -> scanError = error.message ?: "无法读取配对二维码" }
        }
    val actions =
        HostsScreenActions(
            onAddClick = {
                scanError = null
                editorDraft = HostDraft()
            },
            onScanClick = {
                scanError = null
                scanPairingCode()
            },
            onEditClick = { item -> editorDraft = item.toDraft() },
            onDeleteClick = hostsViewModel::deleteHost,
            onTestClick = hostsViewModel::testConnection,
        )

    HostsScreen(
        state = uiState,
        scanError = scanError,
        actions = actions,
    )

    val activeDraft = editorDraft
    if (activeDraft != null) {
        HostEditorDialog(
            initialDraft = activeDraft,
            onDismiss = {
                editorDraft = null
            },
            onSave = { draft ->
                hostsViewModel.saveHost(draft) {
                    onHostSaved()
                    editorDraft = null
                }
            },
        )
    }
}

@Composable
private fun rememberPairingScanner(onResult: (Result<HostDraft>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scanner =
        remember(context) {
            val options =
                GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .enableAutoZoom()
                    .build()
            GmsBarcodeScanning.getClient(context, options)
        }

    return {
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val rawValue = barcode.rawValue
                val result =
                    if (rawValue == null) {
                        Result.failure(IllegalArgumentException("二维码中不包含连接信息"))
                    } else {
                        parseHostPairingPayload(rawValue)
                    }
                onResult(result)
            }
            .addOnFailureListener { error ->
                if (error !is ApiException || error.statusCode != CommonStatusCodes.CANCELED) {
                    onResult(Result.failure(IllegalStateException("无法打开二维码扫描器")))
                }
            }
    }
}

private fun HostProfileItem.toDraft(): HostDraft =
    HostDraft(
        id = profile.id,
        name = profile.name,
        host = profile.host,
        port = profile.port.toString(),
        useTls = profile.useTls,
        shareOrigin = profile.shareOrigin,
    )

private data class HostsScreenActions(
    val onAddClick: () -> Unit,
    val onScanClick: () -> Unit,
    val onEditClick: (HostProfileItem) -> Unit,
    val onDeleteClick: (String) -> Unit,
    val onTestClick: (String) -> Unit,
)

@Composable
private fun HostsScreen(
    state: HostsUiState,
    scanError: String?,
    actions: HostsScreenActions,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HostsHeader(actions = actions)

        HostStateMessages(state)
        scanError?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (state.isLoading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            return
        }

        if (state.profiles.isEmpty()) {
            FirstRunConnectionCard(
                onScanClick = actions.onScanClick,
                onAddClick = actions.onAddClick,
            )
            return
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = state.profiles,
                key = { item -> item.profile.id },
            ) { item ->
                HostCard(
                    item = item,
                    diagnosticResult = state.diagnosticResults[item.profile.id],
                    onEditClick = { actions.onEditClick(item) },
                    onDeleteClick = { actions.onDeleteClick(item.profile.id) },
                    onTestClick = { actions.onTestClick(item.profile.id) },
                )
            }
        }
    }
}

@Composable
private fun HostsHeader(actions: HostsScreenActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "主机",
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.onScanClick) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                )
                Text("扫描二维码")
            }
            Button(onClick = actions.onAddClick) {
                Text("添加主机")
            }
        }
    }
}

@Composable
private fun HostStateMessages(state: HostsUiState) {
    if (state.requiresTokenReentry) {
        Text(
            text = "令牌保护方式已更新。下次使用各连接时，请重新输入令牌。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.errorMessage?.let { errorMessage ->
        Text(
            text = errorMessage,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FirstRunConnectionCard(
    onScanClick: () -> Unit,
    onAddClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("连接你的电脑", style = MaterialTheme.typography.headlineSmall)
            Text(
                text =
                    "继续之前，请在电脑上启动 PiPilot Bridge，" +
                        "并通过 Tailscale 连接电脑和手机。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("在 Bridge 目录中运行 pnpm pair，然后扫描终端显示的二维码。")
            Button(
                onClick = onScanClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                )
                Text("扫描配对二维码")
            }
            TextButton(
                onClick = onAddClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("手动输入连接信息")
            }
        }
    }
}

@Composable
private fun HostCard(
    item: HostProfileItem,
    diagnosticResult: DiagnosticsResult?,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onTestClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.profile.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                DiagnosticStatusIcon(status = item.diagnosticStatus)
            }

            Text(
                text = item.profile.endpoint,
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = if (item.hasToken) "令牌已安全保存" else "尚未配置令牌",
                style = MaterialTheme.typography.bodySmall,
            )

            // Show diagnostic result details if available
            diagnosticResult?.let { result ->
                DiagnosticResultDetail(result = result)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onTestClick,
                    enabled = item.diagnosticStatus != DiagnosticStatus.TESTING,
                ) {
                    if (item.diagnosticStatus == DiagnosticStatus.TESTING) {
                        Text("正在测试…")
                    } else {
                        Text("测试")
                    }
                }
                TextButton(onClick = onEditClick) {
                    Text("编辑")
                }
                TextButton(onClick = onDeleteClick) {
                    Text("删除")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticStatusIcon(status: DiagnosticStatus) {
    when (status) {
        DiagnosticStatus.NONE -> {}
        DiagnosticStatus.TESTING -> {
            CircularProgressIndicator(
                modifier = Modifier.padding(4.dp),
                strokeWidth = 2.dp,
            )
        }
        DiagnosticStatus.SUCCESS -> {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "连接成功",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        DiagnosticStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "连接失败",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DiagnosticResultDetail(result: DiagnosticsResult) {
    val recovery = result.toRecoveryMessage()
    when (result) {
        is DiagnosticsResult.Success -> {
            Column {
                Text(
                    text = recovery.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                result.model?.let {
                    Text(
                        text = "模型：$it",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                result.cwd?.let {
                    Text(
                        text = "工作目录：$it",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        is DiagnosticsResult.NetworkError -> {
            Text(
                text = "${recovery.title}. ${recovery.explanation}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        is DiagnosticsResult.AuthError -> {
            Text(
                text = "${recovery.title}. ${recovery.explanation}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        is DiagnosticsResult.RpcError -> {
            Text(
                text = "${recovery.title}. ${recovery.explanation}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun HostEditorDialog(
    initialDraft: HostDraft,
    onDismiss: () -> Unit,
    onSave: (HostDraft) -> Unit,
) {
    var draft by remember(initialDraft) { mutableStateOf(initialDraft) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialDraft.id == null) "连接你的电脑" else "编辑连接")
        },
        text = {
            HostDraftFields(
                draft = draft,
                onDraftChange = { updatedDraft ->
                    draft = updatedDraft
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) {
                Text(if (initialDraft.id == null) "保存连接" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun HostDraftFields(
    draft: HostDraft,
    onDraftChange: (HostDraft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { newName ->
                onDraftChange(draft.copy(name = newName))
            },
            label = { Text("名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = draft.host,
            onValueChange = { newHost ->
                onDraftChange(draft.copy(host = newHost))
            },
            label = { Text("主机地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = draft.port,
            onValueChange = { newPort ->
                onDraftChange(draft.copy(port = newPort))
            },
            label = { Text("端口") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = draft.token,
            onValueChange = { newToken ->
                onDraftChange(draft.copy(token = newToken))
            },
            label = { Text("令牌") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("使用 TLS 加密")
            Switch(
                checked = draft.useTls,
                onCheckedChange = { checked ->
                    onDraftChange(draft.copy(useTls = checked))
                },
            )
        }
    }
}
