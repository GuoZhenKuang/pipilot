package com.ayagmar.pimobile.ui.chat

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.ayagmar.pimobile.chat.ChatImageSource
import com.ayagmar.pimobile.chat.ChatViewModel
import com.ayagmar.pimobile.chat.ImageEncoder
import com.ayagmar.pimobile.chat.PendingImage
import com.ayagmar.pimobile.chat.PendingQueueItem
import com.ayagmar.pimobile.chat.PendingQueueType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "LongParameterList")
@Composable
internal fun PromptControls(
    isStreaming: Boolean,
    isRetrying: Boolean,
    isDispatchingMessage: Boolean = false,
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
                dispatchActiveRunMessage(inputText, deliveryMode, callbacks)
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag(CHAT_STREAMING_CONTROLS_TAG),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = deliveryMode == ActiveRunDeliveryMode.FOLLOW_UP,
                onClick = { deliveryMode = ActiveRunDeliveryMode.FOLLOW_UP },
                label = { Text("追加消息") },
                enabled = isRunActive,
            )
            FilterChip(
                selected = deliveryMode == ActiveRunDeliveryMode.STEER,
                onClick = { deliveryMode = ActiveRunDeliveryMode.STEER },
                label = { Text("调整方向") },
                enabled = isRunActive && !isRetrying,
            )
            if (isDispatchingMessage) {
                Text(
                    text =
                        when (deliveryMode) {
                            ActiveRunDeliveryMode.FOLLOW_UP -> "正在发送追加消息…"
                            ActiveRunDeliveryMode.STEER -> "正在发送调整方向消息…"
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (isRunActive) {
                TextButton(onClick = if (isRetrying) callbacks.onAbortRetry else callbacks.onAbort) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("停止")
                }
            }
            if (pendingQueueItems.isNotEmpty()) {
                TextButton(onClick = { showQueue = true }) { Text("队列 ${pendingQueueItems.size}") }
            }
        }

        PromptInputRow(
            inputText = inputText,
            isStreaming = isRunActive,
            activeRunDeliveryMode = deliveryMode.takeIf { isRunActive },
            isDispatchingMessage = isDispatchingMessage,
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
                    text = "待发送队列（${pendingItems.size}）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onClear) {
                    Text("清空")
                }
            }

            Text(
                text = "调整方向：${deliveryModeLabel(steeringMode)} · 追加消息：${deliveryModeLabel(followUpMode)}",
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
                text = "这里显示流式生成期间发送的消息；清空操作只会移除本地查看记录。",
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
                    PendingQueueType.STEER -> "调整方向"
                    PendingQueueType.FOLLOW_UP -> "追加消息"
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
            Text("移除")
        }
    }
}

private fun dispatchActiveRunMessage(
    inputText: String,
    deliveryMode: ActiveRunDeliveryMode,
    callbacks: PromptControlsCallbacks,
) {
    val submission = createActiveRunSubmission(inputText, deliveryMode) ?: return
    when (submission.deliveryMode) {
        ActiveRunDeliveryMode.FOLLOW_UP -> callbacks.onFollowUp(submission.message)
        ActiveRunDeliveryMode.STEER -> callbacks.onSteer(submission.message)
    }
}

private fun deliveryModeLabel(mode: String): String {
    return when (mode) {
        ChatViewModel.DELIVERY_MODE_ONE_AT_A_TIME -> "一次一条"
        else -> "全部"
    }
}

@Suppress("LongMethod", "LongParameterList")
@Composable
internal fun PromptInputRow(
    inputText: String,
    isStreaming: Boolean,
    activeRunDeliveryMode: ActiveRunDeliveryMode? = null,
    isDispatchingMessage: Boolean = false,
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
            placeholder = { Text("给 Pi 发送消息") },
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
                        contentDescription = "添加图片",
                    )
                }
            },
            trailingIcon = {
                val canSend = inputText.isNotBlank() || pendingImages.isNotEmpty()
                if (!canSend && !isStreaming) {
                    IconButton(onClick = onShowCommandPalette) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "命令",
                        )
                    }
                } else {
                    IconButton(
                        onClick = onSubmit,
                        enabled = canSend && !isDispatchingMessage,
                    ) {
                        if (isDispatchingMessage) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription =
                                    when (activeRunDeliveryMode) {
                                        ActiveRunDeliveryMode.FOLLOW_UP -> "作为追加消息发送"
                                        ActiveRunDeliveryMode.STEER -> "作为调整方向消息发送"
                                        null -> "发送消息"
                                    },
                            )
                        }
                    }
                }
            },
        )

        previewImageUri?.let { uri ->
            ImagePreviewDialog(
                image = ChatImageSource.LocalUri(uri),
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
                contentDescription = "移除",
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
    image: ChatImageSource,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val presentation by rememberChatImagePresentation(context, image)
    var actionMessage by remember(image) { mutableStateOf<String?>(null) }
    val saveLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(image.mimeType()),
        ) { targetUri ->
            if (targetUri != null) {
                scope.launch {
                    actionMessage =
                        runCatching { image.copyTo(context, targetUri) }
                            .fold(onSuccess = { "图片已保存" }, onFailure = { "无法保存图片" })
                }
            }
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ImagePreviewSurface(
            presentation = presentation,
            actionMessage = actionMessage,
            onSave = {
                actionMessage = null
                saveLauncher.launch(presentation.displayName ?: "pi-mobile-image")
            },
            onShare = {
                actionMessage = null
                scope.launch {
                    runCatching { shareImage(context, image, presentation.mimeType) }
                        .onFailure { actionMessage = "无法分享图片" }
                }
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ImagePreviewSurface(
    presentation: ChatImagePresentation,
    actionMessage: String?,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        ZoomablePreviewImage(presentation)
        ImagePreviewFooter(
            presentation = presentation,
            actionMessage = actionMessage,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        ImagePreviewActions(
            enabled = !presentation.isLoading && presentation.errorMessage == null,
            onSave = onSave,
            onShare = onShare,
            onDismiss = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )
    }
}

@Composable
private fun ZoomablePreviewImage(presentation: ChatImagePresentation) {
    var scale by remember(presentation.model) { mutableFloatStateOf(MIN_IMAGE_SCALE) }
    var offsetX by remember(presentation.model) { mutableFloatStateOf(0f) }
    var offsetY by remember(presentation.model) { mutableFloatStateOf(0f) }
    val transformState =
        rememberTransformableState { _, zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(MIN_IMAGE_SCALE, MAX_IMAGE_SCALE)
            if (scale == MIN_IMAGE_SCALE) {
                offsetX = 0f
                offsetY = 0f
            } else {
                offsetX += panChange.x
                offsetY += panChange.y
            }
        }

    when {
        presentation.isLoading -> CircularProgressIndicator(color = Color.White)
        presentation.errorMessage != null ->
            Text(
                text = presentation.errorMessage,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        else ->
            AsyncImage(
                model = presentation.model,
                contentDescription = presentation.displayName?.let { "预览 $it" } ?: "图片预览",
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        ).transformable(transformState),
                contentScale = ContentScale.Fit,
            )
    }
}

@Composable
private fun ImagePreviewFooter(
    presentation: ChatImagePresentation,
    actionMessage: String?,
    modifier: Modifier = Modifier,
) {
    val metadata = formatImageMetadata(presentation)
    if (presentation.isLoading || (metadata.isBlank() && actionMessage == null)) return
    Text(
        text = actionMessage ?: listOfNotNull(presentation.displayName, metadata).joinToString(" · "),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier =
            modifier
                .background(Color.Black.copy(alpha = IMAGE_METADATA_BACKGROUND_ALPHA))
                .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun ImagePreviewActions(
    enabled: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ImageAction(Icons.Default.Download, "保存图片", enabled, onSave)
        ImageAction(Icons.Default.Share, "分享图片", enabled, onShare)
        ImageAction(Icons.Default.Close, "关闭图片预览", true, onDismiss)
    }
}

@Composable
private fun ImageAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(imageVector = icon, contentDescription = label, tint = Color.White)
    }
}

private suspend fun shareImage(
    context: android.content.Context,
    image: ChatImageSource,
    resolvedMimeType: String?,
) {
    val shareUri = image.createShareUri(context)
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = resolvedMimeType ?: image.mimeType()
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(intent, "分享图片"))
}

private const val MIN_IMAGE_SCALE = 1f
private const val MAX_IMAGE_SCALE = 5f
private const val IMAGE_METADATA_BACKGROUND_ALPHA = 0.72f
