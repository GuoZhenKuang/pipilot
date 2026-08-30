@file:Suppress(
    "LongParameterList",
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "MaxLineLength",
    "ktlint:standard:max-line-length",
)

package com.ayagmar.pimobile.sessions

import com.ayagmar.pimobile.coresessions.SessionIndexSource
import com.ayagmar.pimobile.coresessions.SessionIndexState
import com.ayagmar.pimobile.coresessions.SessionKey
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.hosts.HostProfile
import java.time.Instant

private const val MAX_PRESENTATION_TEXT = 240
private val UNIX_PATH = Regex("(?<![A-Za-z0-9])/(?:[^\\s/]+/)+[^\\s,;:!?)]*")
private val WINDOWS_PATH = Regex("(?i)\\b[A-Z]:\\\\(?:[^\\s\\\\]+\\\\)+[^\\s,;:!?)]*")
private val WHITESPACE = Regex("\\s+")

enum class SessionFreshnessFilter {
    ALL,
    FRESH,
    STALE,
    ERROR,
}

enum class HostSessionStatusKind {
    LOADING,
    REFRESHING,
    FRESH,
    STALE,
    AUTHENTICATION,
    UNREACHABLE,
    ERROR,
}

data class HostSessionStatus(
    val hostId: String,
    val hostLabel: String,
    val kind: HostSessionStatusKind,
    val message: String? = null,
)

data class SessionCockpitFilter(
    val hostId: String? = null,
    val workspaceLabel: String? = null,
    val pinnedOnly: Boolean = false,
    val hiddenOnly: Boolean = false,
    val activeOnly: Boolean = false,
    val freshness: SessionFreshnessFilter = SessionFreshnessFilter.ALL,
)

data class SessionCockpitItem(
    val listKey: String,
    val key: SessionKey?,
    val hostId: String,
    val hostLabel: String,
    val workspaceLabel: String,
    val title: String,
    val preview: String?,
    val model: String?,
    val messageCount: Int?,
    val updatedAt: String?,
    val record: SessionRecord?,
    val isPinned: Boolean,
    val isHidden: Boolean,
    val isActive: Boolean,
    val freshness: HostSessionStatusKind,
    val stableActionDisabledReason: String? = null,
    val isUnavailableSavedItem: Boolean = false,
)

data class SessionCockpitProjection(
    val items: List<SessionCockpitItem>,
    val hostStatuses: List<HostSessionStatus>,
    val workspaceLabels: List<String>,
)

fun buildSessionCockpit(
    hosts: List<HostProfile>,
    states: List<SessionIndexState>,
    saved: SavedSessionsState,
    activeKey: SessionKey?,
    query: String,
    filter: SessionCockpitFilter,
): SessionCockpitProjection {
    val stateByHost = states.associateBy(SessionIndexState::hostId)
    val hostStatuses = hosts.map { host -> stateByHost[host.id].toHostStatus(host) }
    val statusByHost = hostStatuses.associateBy(HostSessionStatus::hostId)
    val items = mutableListOf<SessionCockpitItem>()
    val foundStableKeys = mutableSetOf<SessionKey>()

    hosts.forEach { host ->
        stateByHost[host.id]?.groups.orEmpty().forEach { group ->
            val workspaceLabel = friendlyWorkspaceLabel(group.cwd)
            group.sessions.forEach { session ->
                val key = session.stableKey(host.id)
                if (key != null) foundStableKeys += key
                val title = privacySafeText(session.displayName ?: session.firstUserMessagePreview) ?: "未命名会话"
                val preview =
                    privacySafeText(session.firstUserMessagePreview)
                        ?.takeUnless { it == title }
                items +=
                    SessionCockpitItem(
                        listKey = key?.let { "${it.hostProfileId}:${it.sessionId}" } ?: "legacy:${host.id}:${session.sessionPath}",
                        key = key,
                        hostId = host.id,
                        hostLabel = privacySafeText(host.name) ?: "主机",
                        workspaceLabel = workspaceLabel,
                        title = title,
                        preview = preview,
                        model = privacySafeText(session.lastModel),
                        messageCount = session.messageCount,
                        updatedAt = session.updatedAt,
                        record = session,
                        isPinned = key in saved.pinned,
                        isHidden = key in saved.hidden,
                        isActive = key != null && key == activeKey,
                        freshness = requireNotNull(statusByHost[host.id]).kind,
                        stableActionDisabledReason =
                            if (key == null) {
                                "置顶和隐藏操作需要唯一且稳定的会话标识"
                            } else {
                                null
                            },
                    )
            }
        }
    }

    (saved.pinned + saved.hidden)
        .filter { it !in foundStableKeys && hosts.any { host -> host.id == it.hostProfileId } }
        .forEach { key ->
            val host = hosts.first { it.id == key.hostProfileId }
            items +=
                SessionCockpitItem(
                    listKey = "${key.hostProfileId}:${key.sessionId}",
                    key = key,
                    hostId = host.id,
                    hostLabel = privacySafeText(host.name) ?: "主机",
                    workspaceLabel = "工作区不可用",
                    title = "已保存的会话不可用",
                    preview = null,
                    model = null,
                    messageCount = null,
                    updatedAt = null,
                    record = null,
                    isPinned = key in saved.pinned,
                    isHidden = key in saved.hidden,
                    isActive = key == activeKey,
                    freshness = statusByHost[host.id]?.kind ?: HostSessionStatusKind.ERROR,
                    stableActionDisabledReason = "请刷新此主机或移除已保存的项目",
                    isUnavailableSavedItem = true,
                )
        }

    val normalizedQuery = query.replace(WHITESPACE, " ").trim().take(MAX_PRESENTATION_TEXT).lowercase()
    val filtered =
        items.asSequence()
            .filter { item -> filter.hostId == null || item.hostId == filter.hostId }
            .filter { item -> filter.workspaceLabel == null || item.workspaceLabel == filter.workspaceLabel }
            .filter { item -> if (filter.hiddenOnly) item.isHidden else !item.isHidden }
            .filter { item -> !filter.pinnedOnly || item.isPinned }
            .filter { item -> !filter.activeOnly || item.isActive }
            .filter { item -> item.matches(filter.freshness) }
            .filter {
                    item ->
                normalizedQuery.isBlank() || item.searchableFields().any { it.contains(normalizedQuery) }
            }
            .sortedWith(cockpitComparator)
            .toList()

    return SessionCockpitProjection(
        items = filtered,
        hostStatuses = hostStatuses,
        workspaceLabels = items.map(SessionCockpitItem::workspaceLabel).distinct().sortedBy(String::lowercase),
    )
}

fun privacySafeText(raw: String?): String? {
    val normalized =
        raw?.replace(WINDOWS_PATH, "[path]")?.replace(UNIX_PATH, "[path]")
            ?.replace(WHITESPACE, " ")?.trim()?.take(MAX_PRESENTATION_TEXT)
    return normalized?.takeIf(String::isNotBlank)
}

fun friendlyWorkspaceLabel(cwd: String): String {
    val normalized = cwd.trim().replace('\\', '/').trimEnd('/')
    return privacySafeText(normalized.substringAfterLast('/'))?.takeUnless { it == "[path]" } ?: "工作区"
}

private fun SessionRecord.stableKey(hostId: String): SessionKey? =
    sessionId?.takeIf { hasStableIdentity }?.let { SessionKey(hostId, it) }

private fun SessionIndexState?.toHostStatus(host: HostProfile): HostSessionStatus {
    if (this == null) return HostSessionStatus(host.id, host.name, HostSessionStatusKind.LOADING)
    val error = errorMessage
    val kind =
        when {
            error?.contains("token", ignoreCase = true) == true ||
                error?.contains("auth", ignoreCase = true) == true -> HostSessionStatusKind.AUTHENTICATION
            error?.contains("connect", ignoreCase = true) == true ||
                error?.contains("reach", ignoreCase = true) == true ||
                error?.contains("timeout", ignoreCase = true) == true -> HostSessionStatusKind.UNREACHABLE
            error != null -> HostSessionStatusKind.ERROR
            isRefreshing -> HostSessionStatusKind.REFRESHING
            source == SessionIndexSource.CACHE -> HostSessionStatusKind.STALE
            source == SessionIndexSource.REMOTE -> HostSessionStatusKind.FRESH
            else -> HostSessionStatusKind.LOADING
        }
    return HostSessionStatus(host.id, privacySafeText(host.name) ?: "主机", kind, error?.let(::privacySafeText))
}

private fun SessionCockpitItem.matches(filter: SessionFreshnessFilter): Boolean =
    when (filter) {
        SessionFreshnessFilter.ALL -> true
        SessionFreshnessFilter.FRESH -> freshness == HostSessionStatusKind.FRESH
        SessionFreshnessFilter.STALE -> freshness == HostSessionStatusKind.STALE
        SessionFreshnessFilter.ERROR -> freshness in errorStatuses
    }

private fun SessionCockpitItem.searchableFields(): List<String> =
    listOfNotNull(title, preview, model, hostLabel, workspaceLabel).map(String::lowercase)

private fun updatedEpoch(value: String?): Long =
    runCatching {
        value?.let(Instant::parse)?.toEpochMilli()
    }.getOrNull() ?: 0L

private val cockpitComparator =
    compareByDescending<SessionCockpitItem> { it.isActive }
        .thenByDescending { it.isPinned }
        .thenByDescending { updatedEpoch(it.updatedAt) }
        .thenBy { it.hostLabel.lowercase() }
        .thenBy(SessionCockpitItem::listKey)

private val errorStatuses =
    setOf(
        HostSessionStatusKind.AUTHENTICATION,
        HostSessionStatusKind.UNREACHABLE,
        HostSessionStatusKind.ERROR,
    )
