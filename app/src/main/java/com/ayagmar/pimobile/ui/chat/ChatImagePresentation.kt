package com.ayagmar.pimobile.ui.chat

import android.util.Base64
import androidx.core.net.toUri
import com.ayagmar.pimobile.chat.ChatImageSource

internal fun ChatImageSource.toImageModel(): Any? =
    when (this) {
        is ChatImageSource.LocalUri -> uri.toUri()
        is ChatImageSource.Embedded ->
            runCatching {
                Base64.decode(base64Data, Base64.DEFAULT)
            }.getOrNull()
    }
