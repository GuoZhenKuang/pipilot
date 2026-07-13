package com.ayagmar.pimobile.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.ayagmar.pimobile.chat.ChatViewModel
import com.ayagmar.pimobile.chat.ImageEncoder
import com.ayagmar.pimobile.chat.PendingImage
import com.ayagmar.pimobile.chat.PendingQueueItem
import com.ayagmar.pimobile.chat.PendingQueueType

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "LongParameterList")
@Composable
internal fun PromptControls(
    isStreaming: Boolean,
    isRetrying: Boolean,
    pendingQueueItems: List<PendingQueueItem>,
    steeringMode: String,
    followUpMode: String,
    inputText: String,
    pendingImages: List<PendingImage>,
    callbacks: PromptControlsCallbacks,
) {
    var deliveryMode by rememberSaveable { mutableStateOf(ActiveRunDeliveryMode.FOLLOW_UP) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    val isRunActive = isStreaming || isRetrying
    val submit = {
        if (inputText.isNotBlank()) {
            if (!isRunActive) {
                callbacks.onSendPrompt()
            } else {
                when (deliveryMode) {
                    ActiveRunDeliveryMode.FOLLOW_UP -> callbacks.onFollowUp(inputText)
                    ActiveRunDeliveryMode.STEER -> callbacks.onSteer(inputText)
                }
                callbacks.onInputTextChanged("")
            }
        } else if (!isRunActive && pendingImages.isNotEmpty()) {
            callbacks.onSendPrompt()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().testTag(CHAT_PROMPT_CONTROLS_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag(CHAT_STREAMING_CONTROLS_TAG),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = deliveryMode == ActiveRunDeliveryMode.FOLLOW_UP,
                onClick = { deliveryMode = ActiveRunDeliveryMode.FOLLOW_UP },
                label = { Text("Follow up") },
                enabled = isRunActive,
            )
            FilterChip(
                selected = deliveryMode == ActiveRunDeliveryMode.STEER,
                onClick = { deliveryMode = ActiveRunDeliveryMode.STEER },
                label = { Text("Steer") },
                enabled = isRunActive && !isRetrying,
            )
            if (isRunActive) {
                TextButton(onClick = if (isRetrying) callbacks.onAbortRetry else callbacks.onAbort) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Stop")
                }
            }
            if (pendingQueueItems.isNotEmpty()) {
                TextButton(onClick = { showQueue = true }) { Text("Queue ${pendingQueueItems.size}") }
            }
        }

        PromptInputRow(
            inputText = inputText,
            isStreaming = isRunActive,
            pendingImages = pendingImages,
            onInputTextChanged = callbacks.onInputTextChanged,
            onSubmit = submit,
            onShowCommandPalette = callbacks.onShowCommandPalette,
            onAddImage = callbacks.onAddImage,
            onRemoveImage = callbacks.onRemoveImage,
        )
    }

    if (showQueue) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            PendingQueueInspector(
                pendingItems = pendingQueueItems,
                steeringMode = steeringMode,
                followUpMode = followUpMode,
                onRemoveItem = callbacks.onRemovePendingQueueItem,
                onClear = callbacks.onClearPendingQueueItems,
            )
        }
    }
}

@Composable
private fun PendingQueueInspector(
    pendingItems: List<PendingQueueItem>,
    steeringMode: String,
    followUpMode: String,
    onRemoveItem: (String) -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Pending queue (${pendingItems.size})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onClear) {
                    Text("Clear")
                }
            }

            Text(
                text = "Steer: ${deliveryModeLabel(steeringMode)} · Follow-up: ${deliveryModeLabel(followUpMode)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            pendingItems.forEach { item ->
                PendingQueueItemRow(
                    item = item,
                    onRemove = { onRemoveItem(item.id) },
                )
            }

            Text(
                text = "Items shown here were sent while streaming; clearing only removes local inspector entries.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PendingQueueItemRow(
    item: PendingQueueItem,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val typeLabel =
                when (item.type) {
                    PendingQueueType.STEER -> "Steer"
                    PendingQueueType.FOLLOW_UP -> "Follow-up"
                }
            Text(
                text = "$typeLabel · ${deliveryModeLabel(item.mode)}",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = item.message,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
        }

        TextButton(onClick = onRemove) {
            Text("Remove")
        }
    }
}

private fun deliveryModeLabel(mode: String): String {
    return when (mode) {
        ChatViewModel.DELIVERY_MODE_ONE_AT_A_TIME -> "one-at-a-time"
        else -> "all"
    }
}

@Suppress("LongMethod", "LongParameterList")
@Composable
internal fun PromptInputRow(
    inputText: String,
    isStreaming: Boolean,
    pendingImages: List<PendingImage>,
    onInputTextChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onShowCommandPalette: () -> Unit = {},
    onAddImage: (PendingImage) -> Unit,
    onRemoveImage: (Int) -> Unit,
) {
    val context = LocalContext.current
    val imageEncoder = remember { ImageEncoder(context) }
    var previewImageUri by rememberSaveable { mutableStateOf<String?>(null) }

    val photoPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia(),
        ) { uris ->
            uris.forEach { uri ->
                imageEncoder.getImageInfo(uri)?.let { info -> onAddImage(info) }
            }
        }

    Column(modifier = Modifier.fillMaxWidth().testTag(CHAT_PROMPT_INPUT_ROW_TAG)) {
        // Pending images strip
        if (pendingImages.isNotEmpty()) {
            ImageAttachmentStrip(
                images = pendingImages,
                onRemove = onRemoveImage,
                onImageClick = { uri ->
                    previewImageUri = uri
                },
            )
        }

        OutlinedTextField(
            value = inputText,
            onValueChange = onInputTextChanged,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Message Pi") },
            singleLine = false,
            minLines = 1,
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            enabled = true,
            leadingIcon = {
                IconButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = !isStreaming,
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Attach image",
                    )
                }
            },
            trailingIcon = {
                val canSend = inputText.isNotBlank() || pendingImages.isNotEmpty()
                if (!canSend && !isStreaming) {
                    IconButton(onClick = onShowCommandPalette) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Commands",
                        )
                    }
                } else {
                    IconButton(
                        onClick = onSubmit,
                        enabled = canSend,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                        )
                    }
                }
            },
        )

        previewImageUri?.let { uri ->
            ImagePreviewDialog(
                uriString = uri,
                onDismiss = { previewImageUri = null },
            )
        }
    }
}

@Composable
private fun ImageAttachmentStrip(
    images: List<PendingImage>,
    onRemove: (Int) -> Unit,
    onImageClick: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            items = images,
            key = { index, image -> "${image.uri}-$index" },
        ) { index, image ->
            ImageThumbnail(
                image = image,
                onRemove = { onRemove(index) },
                onClick = { onImageClick(image.uri) },
            )
        }
    }
}

@Suppress("MagicNumber", "LongMethod")
@Composable
private fun ImageThumbnail(
    image: PendingImage,
    onRemove: () -> Unit,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val uri = remember(image.uri) { image.uri.toUri() }
        AsyncImage(
            model = uri,
            contentDescription = image.displayName,
            modifier = Modifier.fillMaxSize().clickable(onClick = onClick),
            contentScale = ContentScale.Crop,
        )

        // Size warning badge
        if (image.sizeBytes > ImageEncoder.MAX_IMAGE_SIZE_BYTES) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.error)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = ">5MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onError,
                )
            }
        }

        // Remove button
        IconButton(
            onClick = onRemove,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .size(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove",
                modifier = Modifier.size(14.dp),
            )
        }

        // File name / size label
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .padding(2.dp),
        ) {
            Text(
                text = formatFileSize(image.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Suppress("MagicNumber")
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1fMB", bytes / 1_048_576.0)
        bytes >= 1_024 -> String.format(java.util.Locale.US, "%.0fKB", bytes / 1_024.0)
        else -> "${bytes}B"
    }
}

@Composable
internal fun ImagePreviewDialog(
    uriString: String,
    onDismiss: () -> Unit,
) {
    val uri = remember(uriString) { uriString.toUri() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = uri,
                contentDescription = "Image preview",
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentScale = ContentScale.Fit,
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close image preview",
                    tint = Color.White,
                )
            }
        }
    }
}
