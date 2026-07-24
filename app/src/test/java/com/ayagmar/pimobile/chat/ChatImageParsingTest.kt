package com.ayagmar.pimobile.chat

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageParsingTest {
    @Test
    fun `extracts documented embedded image content`() {
        val content =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "image")
                        put("data", "aW1hZ2U=")
                        put("mimeType", "image/png")
                    },
                )
            }

        assertEquals(
            listOf(ChatImageSource.Embedded(base64Data = "aW1hZ2U=", mimeType = "image/png")),
            extractUserImages(content),
        )
    }

    @Test
    fun `ignores malformed and non-image embedded content`() {
        val content =
            buildJsonArray {
                add(buildJsonObject { put("type", "image") })
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("data", "aW1hZ2U=")
                        put("mimeType", "image/png")
                    },
                )
            }

        assertTrue(extractUserImages(content).isEmpty())
    }
}
