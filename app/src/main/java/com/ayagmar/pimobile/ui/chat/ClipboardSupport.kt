package com.ayagmar.pimobile.ui.chat

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch

@Composable
internal fun rememberClipboardCopy(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { text ->
            scope.launch {
                clipboard.setClipEntry(
                    ClipEntry(ClipData.newPlainText(CLIPBOARD_LABEL, text)),
                )
            }
        }
    }
}

private const val CLIPBOARD_LABEL = "PiPilot"
