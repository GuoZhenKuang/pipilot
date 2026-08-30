package top.guozk.pipilot.sessions

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SessionEntryProjectionTest {
    @Test
    fun appendsEntriesWithoutDuplicatingMessages() {
        val projection = SessionEntryProjection()
        val initial = projection.apply(entriesData(listOf(messageEntry("m1", null, "first")), "m1"), true)
        val incremental = projection.apply(entriesData(listOf(messageEntry("m2", "m1", "second")), "m2"), false)

        assertEquals(listOf("first"), initial.messagesText())
        assertEquals(listOf("first", "second"), incremental.messagesText())
    }

    @Test
    fun requiresRebuildWhenActiveBranchMoves() {
        val projection = SessionEntryProjection()
        projection.apply(
            entriesData(
                listOf(
                    messageEntry("root", null, "root"),
                    messageEntry("left", "root", "left"),
                ),
                "left",
            ),
            true,
        )

        val update = projection.apply(entriesData(listOf(messageEntry("right", "root", "right")), "right"), false)

        assertSame(ProjectionUpdate.RebuildRequired, update)
    }

    @Test
    fun requiresRebuildForUnknownEntryVariant() {
        val projection = SessionEntryProjection()
        val unknown =
            buildJsonObject {
                put("type", "future_entry")
                put("id", "future-1")
                put("parentId", JsonNull)
            }

        assertSame(
            ProjectionUpdate.RebuildRequired,
            projection.apply(entriesData(listOf(unknown), "future-1"), true),
        )
    }

    @Test
    fun followsFirstKeptEntryDuringCompaction() {
        val projection = SessionEntryProjection()
        val compaction =
            buildJsonObject {
                put("type", "compaction")
                put("id", "c1")
                put("parentId", "m2")
                put("timestamp", "2026-01-01T00:00:03Z")
                put("summary", "earlier summary")
                put("firstKeptEntryId", "m2")
                put("tokensBefore", 100)
            }
        val update =
            projection.apply(
                entriesData(
                    listOf(
                        messageEntry("m1", null, "discarded"),
                        messageEntry("m2", "m1", "kept"),
                        compaction,
                        messageEntry("m3", "c1", "after"),
                    ),
                    "m3",
                ),
                true,
            ) as ProjectionUpdate.Applied

        assertEquals(listOf("compactionSummary", "user", "user"), update.messages.map { it.jsonObject.string("role") })
        assertEquals(listOf("kept", "after"), update.messagesText())
    }

    private fun entriesData(
        entries: List<kotlinx.serialization.json.JsonObject>,
        leafId: String,
    ) = buildJsonObject {
        put("entries", buildJsonArray { entries.forEach(::add) })
        put("leafId", leafId)
    }

    private fun messageEntry(
        id: String,
        parentId: String?,
        text: String,
    ) = buildJsonObject {
        put("type", "message")
        put("id", id)
        if (parentId == null) put("parentId", JsonNull) else put("parentId", parentId)
        put("timestamp", "2026-01-01T00:00:00Z")
        put(
            "message",
            buildJsonObject {
                put("role", "user")
                put("content", text)
                put("timestamp", 1L)
            },
        )
    }

    private fun ProjectionUpdate.messagesText(): List<String> {
        return (this as ProjectionUpdate.Applied).messages.mapNotNull { message ->
            message.jsonObject["content"]?.jsonPrimitive?.content
        }
    }

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String {
        return getValue(name).jsonPrimitive.content
    }
}
