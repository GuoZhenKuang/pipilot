package com.ayagmar.pimobile.ui.hosts

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
import com.ayagmar.pimobile.hosts.ConnectionDiagnostics
import com.ayagmar.pimobile.hosts.DiagnosticStatus
import com.ayagmar.pimobile.hosts.DiagnosticsResult
import com.ayagmar.pimobile.hosts.HostDraft
import com.ayagmar.pimobile.hosts.HostProfileItem
import com.ayagmar.pimobile.hosts.HostProfileStore
import com.ayagmar.pimobile.hosts.HostTokenStore
import com.ayagmar.pimobile.hosts.HostsUiState
import com.ayagmar.pimobile.hosts.HostsViewModel
import com.ayagmar.pimobile.hosts.HostsViewModelFactory
import com.ayagmar.pimobile.hosts.parseHostPairingPayload
import com.ayagmar.pimobile.hosts.toRecoveryMessage
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

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
                .onFailure { error -> scanError = error.message ?: "Could not read pairing QR" }
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
                        Result.failure(IllegalArgumentException("The QR code did not contain connection details"))
                    } else {
                        parseHostPairingPayload(rawValue)
                    }
                onResult(result)
            }
            .addOnFailureListener { error ->
                if (error !is ApiException || error.statusCode != CommonStatusCodes.CANCELED) {
                    onResult(Result.failure(IllegalStateException("Could not open the QR scanner")))
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
            text = "Hosts",
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.onScanClick) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                )
                Text("Scan QR")
            }
            Button(onClick = actions.onAddClick) {
                Text("Add host")
            }
        }
    }
}

@Composable
private fun HostStateMessages(state: HostsUiState) {
    if (state.requiresTokenReentry) {
        Text(
            text = "Token protection was updated. Re-enter a token when you next use each connection.",
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
            Text("Connect your computer", style = MaterialTheme.typography.headlineSmall)
            Text(
                text =
                    "Before continuing, start the Pi Mobile bridge on your computer " +
                        "and connect both devices through Tailscale.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Run pnpm pair in the bridge folder, then scan the code shown in your terminal.")
            Button(
                onClick = onScanClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                )
                Text("Scan pairing QR")
            }
            TextButton(
                onClick = onAddClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Enter connection manually")
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
                text = if (item.hasToken) "Token stored securely" else "No token configured",
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
                        Text("Testing...")
                    } else {
                        Text("Test")
                    }
                }
                TextButton(onClick = onEditClick) {
                    Text("Edit")
                }
                TextButton(onClick = onDeleteClick) {
                    Text("Delete")
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
                contentDescription = "Connection successful",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        DiagnosticStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Connection failed",
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
                        text = "Model: $it",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                result.cwd?.let {
                    Text(
                        text = "CWD: $it",
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
            Text(if (initialDraft.id == null) "Connect your computer" else "Edit connection")
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
                Text(if (initialDraft.id == null) "Save connection" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = draft.host,
            onValueChange = { newHost ->
                onDraftChange(draft.copy(host = newHost))
            },
            label = { Text("Host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = draft.port,
            onValueChange = { newPort ->
                onDraftChange(draft.copy(port = newPort))
            },
            label = { Text("Port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = draft.token,
            onValueChange = { newToken ->
                onDraftChange(draft.copy(token = newToken))
            },
            label = { Text("Token") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Use TLS")
            Switch(
                checked = draft.useTls,
                onCheckedChange = { checked ->
                    onDraftChange(draft.copy(useTls = checked))
                },
            )
        }
    }
}
