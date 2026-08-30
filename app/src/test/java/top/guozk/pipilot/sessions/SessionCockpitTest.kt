@file:Suppress("LongParameterList", "MaxLineLength", "ktlint:standard:max-line-length")

package top.guozk.pipilot.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.guozk.pipilot.coresessions.SessionGroup
import top.guozk.pipilot.coresessions.SessionIndexSource
import top.guozk.pipilot.coresessions.SessionIndexState
import top.guozk.pipilot.coresessions.SessionKey
import top.guozk.pipilot.coresessions.SessionRecord
import top.guozk.pipilot.hosts.HostProfile

class SessionCockpitTest {
    private val alpha = HostProfile("host-a", "Alpha host", "alpha", 8787, false)
    private val beta = HostProfile("host-b", "Beta host", "beta", 8787, false)

    @Test
    fun `presentation and search never use absolute path or full cwd`() {
        val privatePath = "/home/private/work/payment/session.jsonl"
        val record = stableRecord("session-a", privatePath, "/home/private/work/payment", displayName = null)
        val projection = project(listOf(alpha), listOf(state(alpha, record)))
        val item = projection.items.single()

        assertEquals("Fix [path]", item.title)
        assertEquals("payment", item.workspaceLabel)
        val rendered = listOfNotNull(item.title, item.preview, item.hostLabel, item.workspaceLabel).joinToString(" ")
        assertFalse(rendered.contains(privatePath))
        assertFalse(rendered.contains("/home/private"))
        assertTrue(project(listOf(alpha), listOf(state(alpha, record)), query = privatePath).items.isEmpty())
        assertEquals(1, project(listOf(alpha), listOf(state(alpha, record)), query = "payment").items.size)
    }

    @Test
    fun `stable list key survives metadata refresh and session move`() {
        val before = stableRecord("session-a", "/one/file.jsonl", "/work/project", displayName = "Before")
        val after = before.copy(sessionPath = "/moved/file.jsonl", displayName = "After")

        val beforeItem = project(listOf(alpha), listOf(state(alpha, before))).items.single()
        val afterItem = project(listOf(alpha), listOf(state(alpha, after))).items.single()

        assertEquals(beforeItem.listKey, afterItem.listKey)
        assertNotEquals(beforeItem.title, afterItem.title)
    }

    @Test
    fun `ordering is active then pinned then update host and key`() {
        val active = stableRecord("active", "/a", "/work/a", "Active", "2026-01-01T00:00:00Z")
        val pinned = stableRecord("pinned", "/b", "/work/b", "Pinned", "2026-01-03T00:00:00Z")
        val recent = stableRecord("recent", "/c", "/work/c", "Recent", "2026-01-04T00:00:00Z")
        val saved = SavedSessionsState(pinned = setOf(SessionKey(beta.id, "pinned")))

        val items =
            project(
                listOf(alpha, beta),
                listOf(state(alpha, active, recent), state(beta, pinned)),
                saved = saved,
                activeKey = SessionKey(alpha.id, "active"),
            ).items

        assertEquals(listOf("Active", "Pinned", "Recent"), items.map { it.title })
    }

    @Test
    fun `hidden recovery and stale placeholders never rebind duplicate identities`() {
        val duplicateA = stableRecord("duplicate", "/a", "/work/a", "A").copy(isSessionIdUnique = false)
        val duplicateB = stableRecord("duplicate", "/b", "/work/b", "B").copy(isSessionIdUnique = false)
        val key = SessionKey(alpha.id, "duplicate")
        val normal =
            project(
                listOf(alpha),
                listOf(state(alpha, duplicateA, duplicateB)),
                SavedSessionsState(hidden = setOf(key)),
            )
        assertEquals(2, normal.items.size)
        assertTrue(normal.items.all { it.key == null })

        val hidden =
            project(
                listOf(alpha),
                listOf(state(alpha, duplicateA, duplicateB)),
                SavedSessionsState(hidden = setOf(key)),
                filter = SessionCockpitFilter(hiddenOnly = true),
            )
        assertEquals(1, hidden.items.size)
        assertTrue(hidden.items.single().isUnavailableSavedItem)
        assertEquals(key, hidden.items.single().key)
    }

    @Test
    fun `host workspace saved active freshness and query filters compose deterministically`() {
        val alphaRecord = stableRecord("alpha", "/alpha", "/work/alpha-space", "Alpha task")
        val betaRecord = stableRecord("beta", "/beta", "/work/beta-space", "Beta task")
        val saved = SavedSessionsState(pinned = setOf(SessionKey(beta.id, "beta")))
        val states =
            listOf(
                state(alpha, alphaRecord, source = SessionIndexSource.CACHE),
                state(beta, betaRecord, source = SessionIndexSource.REMOTE),
            )

        assertEquals(
            listOf("Beta task"),
            project(
                listOf(alpha, beta),
                states,
                saved,
                filter = SessionCockpitFilter(hostId = beta.id, pinnedOnly = true),
            ).items.map { it.title },
        )
        assertEquals(
            listOf("Alpha task"),
            project(
                listOf(alpha, beta),
                states,
                activeKey = SessionKey(alpha.id, "alpha"),
                filter = SessionCockpitFilter(workspaceLabel = "alpha-space", activeOnly = true),
            ).items.map { it.title },
        )
        assertEquals(
            listOf("Alpha task"),
            project(
                listOf(alpha, beta),
                states,
                query = "alpha host",
                filter = SessionCockpitFilter(freshness = SessionFreshnessFilter.STALE),
            ).items.map { it.title },
        )
    }

    @Test
    fun `one host failure keeps cached results from another host`() {
        val cached = state(alpha, stableRecord("cached", "/a", "/work/a", "Cached"), source = SessionIndexSource.CACHE)
        val failed = SessionIndexState(beta.id, errorMessage = "authentication failed")
        val projection = project(listOf(alpha, beta), listOf(cached, failed))

        assertEquals(listOf("Cached"), projection.items.map { it.title })
        assertEquals(HostSessionStatusKind.STALE, projection.hostStatuses.first { it.hostId == alpha.id }.kind)
        assertEquals(HostSessionStatusKind.AUTHENTICATION, projection.hostStatuses.first { it.hostId == beta.id }.kind)
    }

    private fun project(
        hosts: List<HostProfile>,
        states: List<SessionIndexState>,
        saved: SavedSessionsState = SavedSessionsState(),
        activeKey: SessionKey? = null,
        query: String = "",
        filter: SessionCockpitFilter = SessionCockpitFilter(),
    ) = buildSessionCockpit(hosts, states, saved, activeKey, query, filter)

    private fun state(
        host: HostProfile,
        vararg records: SessionRecord,
        source: SessionIndexSource = SessionIndexSource.REMOTE,
    ) = SessionIndexState(
        host.id,
        listOf(SessionGroup(records.firstOrNull()?.cwd.orEmpty(), records.toList())),
        source = source,
    )

    private fun stableRecord(
        id: String,
        path: String,
        cwd: String,
        displayName: String?,
        updatedAt: String = "2026-01-02T00:00:00Z",
    ) = SessionRecord(
        sessionPath = path,
        cwd = cwd,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = updatedAt,
        displayName = displayName,
        firstUserMessagePreview = "Fix /home/private/secret.txt",
        sessionId = id,
        isSessionIdUnique = true,
    )
}
