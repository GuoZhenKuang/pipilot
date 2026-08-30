package top.guozk.pipilot.corenet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import top.guozk.pipilot.corerpc.AbortRetryCommand
import top.guozk.pipilot.corerpc.CloneCommand
import top.guozk.pipilot.corerpc.CycleModelCommand
import top.guozk.pipilot.corerpc.CycleThinkingLevelCommand
import top.guozk.pipilot.corerpc.GetEntriesCommand
import top.guozk.pipilot.corerpc.GetLastAssistantTextCommand
import top.guozk.pipilot.corerpc.GetTreeCommand
import top.guozk.pipilot.corerpc.NewSessionCommand
import top.guozk.pipilot.corerpc.SetFollowUpModeCommand
import top.guozk.pipilot.corerpc.SetSteeringModeCommand
import top.guozk.pipilot.corerpc.SetThinkingLevelCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class RpcCommandEncodingTest {
    @Test
    fun `encodes current session topology commands`() {
        val entries = encodeRpcCommand(Json, GetEntriesCommand(id = "entries-1", since = "entry-1"))
        val tree = encodeRpcCommand(Json, GetTreeCommand(id = "tree-1"))
        val clone = encodeRpcCommand(Json, CloneCommand(id = "clone-1"))

        assertEquals("get_entries", entries["type"]?.jsonPrimitive?.content)
        assertEquals("entry-1", entries["since"]?.jsonPrimitive?.content)
        assertEquals("get_tree", tree["type"]?.jsonPrimitive?.content)
        assertEquals("clone", clone["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `encodes cycle model command`() {
        val encoded = encodeRpcCommand(Json, CycleModelCommand(id = "cycle-1"))

        assertEquals("cycle_model", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("cycle-1", encoded["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `encodes cycle thinking level command`() {
        val encoded = encodeRpcCommand(Json, CycleThinkingLevelCommand(id = "thinking-1"))

        assertEquals("cycle_thinking_level", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("thinking-1", encoded["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `encodes new session command`() {
        val encoded = encodeRpcCommand(Json, NewSessionCommand(id = "new-1"))

        assertEquals("new_session", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("new-1", encoded["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `encodes set steering mode command`() {
        val encoded = encodeRpcCommand(Json, SetSteeringModeCommand(id = "steer-mode-1", mode = "all"))

        assertEquals("set_steering_mode", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("steer-mode-1", encoded["id"]?.jsonPrimitive?.content)
        assertEquals("all", encoded["mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `encodes set follow up mode command`() {
        val encoded = encodeRpcCommand(Json, SetFollowUpModeCommand(id = "follow-up-mode-1", mode = "one-at-a-time"))

        assertEquals("set_follow_up_mode", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("follow-up-mode-1", encoded["id"]?.jsonPrimitive?.content)
        assertEquals("one-at-a-time", encoded["mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `encodes set thinking level command`() {
        val encoded = encodeRpcCommand(Json, SetThinkingLevelCommand(id = "set-thinking-1", level = "high"))

        assertEquals("set_thinking_level", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("set-thinking-1", encoded["id"]?.jsonPrimitive?.content)
        assertEquals("high", encoded["level"]?.jsonPrimitive?.content)
    }

    @Test
    fun `encodes get last assistant text command`() {
        val encoded = encodeRpcCommand(Json, GetLastAssistantTextCommand(id = "copy-1"))

        assertEquals("get_last_assistant_text", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("copy-1", encoded["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `encodes abort retry command`() {
        val encoded = encodeRpcCommand(Json, AbortRetryCommand(id = "abort-retry-1"))

        assertEquals("abort_retry", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("abort-retry-1", encoded["id"]?.jsonPrimitive?.content)
    }
}
