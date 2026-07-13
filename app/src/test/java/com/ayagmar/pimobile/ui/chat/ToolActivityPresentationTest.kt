package com.ayagmar.pimobile.ui.chat

import com.ayagmar.pimobile.chat.ChatTimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolActivityPresentationTest {
    @Test
    fun `formats known tool targets`() {
        assertEquals("Read src/Main.kt", presentToolActivity(tool("read", mapOf("path" to "src/Main.kt"))).summary)
        assertEquals("Edited a.kt", presentToolActivity(tool("edit", mapOf("file_path" to "a.kt"))).summary)
        assertEquals("Wrote out.txt", presentToolActivity(tool("write", mapOf("target" to "out.txt"))).summary)
    }

    @Test
    fun `summarizes bash command and state`() {
        val presentation =
            presentToolActivity(
                tool("bash", mapOf("command" to "./gradlew test\nignored"), streaming = true),
            )
        assertEquals("./gradlew test", presentation.summary)
        assertEquals(ToolActivityStatus.RUNNING, presentation.status)
    }

    @Test
    fun `unknown tool uses only its public name`() {
        val presentation = presentToolActivity(tool("custom", mapOf("token" to "secret")))
        assertEquals("custom", presentation.summary)
        assertFalse(presentation.summary.contains("secret"))
    }

    @Test
    fun `errors and detail availability are retained`() {
        val presentation = presentToolActivity(tool("read", error = true, output = "failed"))
        assertEquals(ToolActivityStatus.ERROR, presentation.status)
        assertTrue(presentation.hasDetails)
    }

    private fun tool(
        name: String,
        arguments: Map<String, String> = emptyMap(),
        streaming: Boolean = false,
        error: Boolean = false,
        output: String = "",
    ) = ChatTimelineItem.Tool(
        id = name,
        toolName = name,
        output = output,
        isCollapsed = true,
        isStreaming = streaming,
        isError = error,
        arguments = arguments,
    )
}
