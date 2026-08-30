package top.guozk.pipilot.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.guozk.pipilot.coresessions.SessionRecord
import top.guozk.pipilot.sessions.ForkableMessage
import top.guozk.pipilot.sessions.privacySafeText

@Composable
@Suppress("LongParameterList")
fun SessionActionsRow(
    isBusy: Boolean,
    onRenameClick: () -> Unit,
    onForkClick: () -> Unit,
    onExportClick: () -> Unit,
    onCompactClick: () -> Unit,
    onShareClick: () -> Unit = {},
    onRevokeShareClick: () -> Unit = {},
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = onRenameClick, enabled = !isBusy) {
                Text("重命名")
            }
        }
        item {
            TextButton(onClick = onForkClick, enabled = !isBusy) {
                Text("分叉")
            }
        }
        item {
            TextButton(onClick = onExportClick, enabled = !isBusy) {
                Text("导出")
            }
        }
        item {
            TextButton(onClick = onCompactClick, enabled = !isBusy) {
                Text("压缩上下文")
            }
        }
        item {
            TextButton(onClick = onShareClick, enabled = !isBusy) {
                Text("分享会话链接")
            }
        }
        item {
            TextButton(onClick = onRevokeShareClick, enabled = !isBusy) {
                Text("撤销分享链接")
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
fun RenameSessionDialog(
    currentSession: SessionRecord?,
    name: String,
    isBusy: Boolean,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名当前会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                currentSession?.let { session ->
                    Text(
                        text = session.displayTitle,
                    )
                    session.displaySubtitle?.let { subtitle ->
                        Text(subtitle)
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("会话名称") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isBusy && name.isNotBlank(),
            ) {
                Text("重命名")
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
fun ForkPickerDialog(
    isLoading: Boolean,
    candidates: List<ForkableMessage>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从消息处分叉") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(
                            items = candidates,
                            key = { candidate -> candidate.entryId },
                        ) { candidate ->
                            TextButton(
                                onClick = { onSelect(candidate.entryId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(privacySafeText(candidate.preview) ?: "用户消息")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

val SessionRecord.displayTitle: String
    get() = privacySafeText(displayName ?: firstUserMessagePreview) ?: "未命名会话"

val SessionRecord.displaySubtitle: String?
    get() =
        privacySafeText(firstUserMessagePreview)
            ?.takeIf { !displayName.isNullOrBlank() && it != displayTitle }
