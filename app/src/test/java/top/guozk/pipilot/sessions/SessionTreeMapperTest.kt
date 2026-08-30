package top.guozk.pipilot.sessions

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTreeMapperTest {
    @Test
    fun mapsCanonicalRpcTreeAndResolvesFilteredLeaf() {
        val snapshot =
            parseRpcSessionTreeSnapshot(
                data =
                    buildJsonObject {
                        put("leafId", "tool-1")
                        put(
                            "tree",
                            buildJsonArray {
                                add(
                                    treeNode(
                                        id = "user-1",
                                        parentId = null,
                                        role = "user",
                                        text = "Start here",
                                        children =
                                            buildJsonArray {
                                                add(
                                                    treeNode(
                                                        id = "tool-1",
                                                        parentId = "user-1",
                                                        role = "toolResult",
                                                        text = "tool output",
                                                    ),
                                                )
                                            },
                                    ),
                                )
                            },
                        )
                    },
                sessionPath = "/tmp/session.jsonl",
                filter = "no-tools",
            )

        assertEquals("/tmp/session.jsonl", snapshot.sessionPath)
        assertEquals(listOf("user-1"), snapshot.rootIds)
        assertEquals("user-1", snapshot.currentLeafId)
        assertEquals(listOf("user-1"), snapshot.entries.map { entry -> entry.entryId })
        assertEquals("Start here", snapshot.entries.single().preview)
    }

    private fun treeNode(
        id: String,
        parentId: String?,
        role: String,
        text: String,
        children: kotlinx.serialization.json.JsonArray = buildJsonArray {},
    ) = buildJsonObject {
        put(
            "entry",
            buildJsonObject {
                put("type", "message")
                put("id", id)
                if (parentId == null) put("parentId", JsonNull) else put("parentId", parentId)
                put("timestamp", "2026-01-01T00:00:00.000Z")
                put(
                    "message",
                    buildJsonObject {
                        put("role", role)
                        put("content", text)
                    },
                )
            },
        )
        put("children", children)
    }
}
