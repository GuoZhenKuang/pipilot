package com.ayagmar.pimobile.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.ayagmar.pimobile.chat.ChatImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ChatImagePresentation(
    val model: Any? = null,
    val displayName: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

@Composable
internal fun rememberChatImagePresentation(
    context: Context,
    image: ChatImageSource,
): State<ChatImagePresentation> =
    produceState(
        initialValue = ChatImagePresentation(),
        key1 = image,
    ) {
        value =
            withContext(Dispatchers.IO) {
                runCatching { resolveImagePresentation(context, image) }
                    .getOrElse {
                        ChatImagePresentation(
                            isLoading = false,
                            errorMessage = "图片预览不可用",
                        )
                    }
            }
    }

internal suspend fun ChatImageSource.copyTo(
    context: Context,
    target: Uri,
) = withContext(Dispatchers.IO) {
    context.contentResolver.openOutputStream(target)?.use { output ->
        when (this@copyTo) {
            is ChatImageSource.LocalUri ->
                context.contentResolver.openInputStream(uri.toUri())?.use { input ->
                    input.copyTo(output)
                } ?: error("无法读取图片")
            is ChatImageSource.Embedded -> output.write(resolveEmbeddedBytes(this@copyTo))
        }
    } ?: error("无法创建图片")
}

internal suspend fun ChatImageSource.createShareUri(context: Context): Uri =
    withContext(Dispatchers.IO) {
        val extension = mimeType().substringAfter('/', "img").substringBefore('+')
        val shareDirectory = File(context.cacheDir, "shared-images").apply { mkdirs() }
        val file = File(shareDirectory, "pi-mobile-image.$extension")
        file.outputStream().use { output ->
            when (this@createShareUri) {
                is ChatImageSource.LocalUri ->
                    context.contentResolver.openInputStream(uri.toUri())?.use { input ->
                        input.copyTo(output)
                    } ?: error("无法读取图片")
                is ChatImageSource.Embedded -> output.write(resolveEmbeddedBytes(this@createShareUri))
            }
        }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

internal fun ChatImageSource.mimeType(): String =
    when (this) {
        is ChatImageSource.LocalUri -> "image/*"
        is ChatImageSource.Embedded -> mimeType
    }

internal fun formatImageMetadata(presentation: ChatImagePresentation): String =
    buildList {
        if (presentation.width != null && presentation.height != null) {
            add("${presentation.width} × ${presentation.height}")
        }
        presentation.sizeBytes?.let { add(formatImageSize(it)) }
        presentation.mimeType?.substringAfter('/')?.uppercase()?.let { add(it) }
    }.joinToString(" · ")

private fun resolveImagePresentation(
    context: Context,
    image: ChatImageSource,
): ChatImagePresentation =
    when (image) {
        is ChatImageSource.LocalUri -> resolveLocalImage(context, image.uri.toUri())
        is ChatImageSource.Embedded -> resolveEmbeddedImage(image)
    }

private fun resolveLocalImage(
    context: Context,
    uri: Uri,
): ChatImagePresentation {
    var displayName: String? = null
    var sizeBytes: Long? = null
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
            }
        }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    return ChatImagePresentation(
        model = uri,
        displayName = displayName,
        mimeType = context.contentResolver.getType(uri),
        sizeBytes = sizeBytes,
        width = bounds.outWidth.takeIf { it > 0 },
        height = bounds.outHeight.takeIf { it > 0 },
        isLoading = false,
    )
}

private fun resolveEmbeddedImage(image: ChatImageSource.Embedded): ChatImagePresentation {
    val bytes = resolveEmbeddedBytes(image)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val extension = image.mimeType.substringAfter('/', "img").substringBefore('+')
    return ChatImagePresentation(
        model = bytes,
        displayName = "session-image.$extension",
        mimeType = image.mimeType,
        sizeBytes = bytes.size.toLong(),
        width = bounds.outWidth.takeIf { it > 0 },
        height = bounds.outHeight.takeIf { it > 0 },
        isLoading = false,
    )
}

private fun resolveEmbeddedBytes(image: ChatImageSource.Embedded): ByteArray {
    val cacheKey = "${image.mimeType}:${image.base64Data.length}:${image.base64Data.hashCode()}"
    return embeddedImageCache[cacheKey]
        ?: Base64.decode(image.base64Data, Base64.DEFAULT).also { decoded ->
            embeddedImageCache.put(cacheKey, decoded)
        }
}

private fun formatImageSize(bytes: Long): String =
    when {
        bytes >= BYTES_PER_MEGABYTE -> String.format(java.util.Locale.US, "%.1f MB", bytes / BYTES_PER_MEGABYTE)
        bytes >= BYTES_PER_KILOBYTE -> String.format(java.util.Locale.US, "%.0f KB", bytes / BYTES_PER_KILOBYTE)
        else -> "$bytes B"
    }

private val embeddedImageCache =
    object : LruCache<String, ByteArray>(EMBEDDED_IMAGE_CACHE_BYTES) {
        override fun sizeOf(
            key: String,
            value: ByteArray,
        ): Int = value.size
    }

private const val EMBEDDED_IMAGE_CACHE_BYTES = 16 * 1024 * 1024
private const val BYTES_PER_KILOBYTE = 1024.0
private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
