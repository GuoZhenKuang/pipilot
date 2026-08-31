package top.guozk.pipilot.coresessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionLineageResolverTest {
    private fun session(
        path: String,
        id: String?,
        parentPath: String? = null,
        unique: Boolean = true,
    ): SessionRecord =
        SessionRecord(
            sessionPath = path,
            cwd = "/work",
            createdAt = "2026-08-31T00:00:00Z",
            updatedAt = "2026-08-31T00:00:00Z",
            sessionId = id,
            isSessionIdUnique = unique,
            parentSessionPath = parentPath,
        )

    private val root = SessionGroup("/work", emptyList())

    @Test
    fun `live parent resolves to stable id`() {
        val groups =
            listOf(
                SessionGroup(
                    "/work",
                    listOf(
                        session("/s/parent.jsonl", "id-parent"),
                        session("/s/child.jsonl", "id-child", parentPath = "/s/parent.jsonl"),
                    ),
                ),
            )

        val lineage = SessionLineageResolver.resolve(groups)

        assertEquals(SessionLineage("id-parent", LineageStatus.LIVE), lineage["id-child"])
        assertNull(lineage["id-parent"])
    }

    @Test
    fun `missing parent path yields MISSING`() {
        val groups =
            listOf(
                SessionGroup(
                    "/work",
                    listOf(session("/s/child.jsonl", "id-child", parentPath = "/s/gone.jsonl")),
                ),
            )

        val lineage = SessionLineageResolver.resolve(groups)

        assertEquals(SessionLineage(null, LineageStatus.MISSING), lineage["id-child"])
    }

    @Test
    fun `self parent yields CYCLE`() {
        val groups =
            listOf(
                SessionGroup(
                    "/work",
                    listOf(session("/s/self.jsonl", "id-self", parentPath = "/s/self.jsonl")),
                ),
            )

        val lineage = SessionLineageResolver.resolve(groups)

        assertEquals(SessionLineage(null, LineageStatus.CYCLE), lineage["id-self"])
    }

    @Test
    fun `two node cycle marks both`() {
        val groups =
            listOf(
                SessionGroup(
                    "/work",
                    listOf(
                        session("/s/a.jsonl", "id-a", parentPath = "/s/b.jsonl"),
                        session("/s/b.jsonl", "id-b", parentPath = "/s/a.jsonl"),
                    ),
                ),
            )

        val lineage = SessionLineageResolver.resolve(groups)

        assertEquals(LineageStatus.CYCLE, lineage["id-a"]?.status)
        assertEquals(LineageStatus.CYCLE, lineage["id-b"]?.status)
    }

    @Test
    fun `duplicate id parent is not resolvable`() {
        val groups =
            listOf(
                SessionGroup(
                    "/work",
                    listOf(
                        session("/s/dup1.jsonl", "same-id"),
                        session("/s/dup2.jsonl", "same-id"),
                        session("/s/child.jsonl", "id-child", parentPath = "/s/dup1.jsonl"),
                    ),
                ),
            )

        val lineage = SessionLineageResolver.resolve(groups)

        // 重复 ID 的路径解析被跳过 → 精确路径 dup1 不在唯一稳定映射中 → MISSING
        assertEquals(SessionLineage(null, LineageStatus.MISSING), lineage["id-child"])
    }

    @Test
    fun `windows separators normalize`() {
        val groups =
            listOf(
                SessionGroup(
                    "/work",
                    listOf(
                        session("/s/parent.jsonl", "id-parent"),
                        session("/s/child.jsonl", "id-child", parentPath = "\\s\\parent.jsonl"),
                    ),
                ),
            )

        val lineage = SessionLineageResolver.resolve(groups)

        assertEquals(SessionLineage("id-parent", LineageStatus.LIVE), lineage["id-child"])
    }
}
