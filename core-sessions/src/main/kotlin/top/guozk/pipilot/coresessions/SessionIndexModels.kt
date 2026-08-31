package top.guozk.pipilot.coresessions

import kotlinx.serialization.Serializable

@Serializable
data class SessionRecord(
    val sessionPath: String,
    val cwd: String,
    val createdAt: String,
    val updatedAt: String,
    val displayName: String? = null,
    val firstUserMessagePreview: String? = null,
    val messageCount: Int? = null,
    val lastModel: String? = null,
    /** Documented Pi session-header identity. Null keeps old caches backward compatible. */
    val sessionId: String? = null,
    /** False for old caches, malformed IDs, and every member of a duplicate-ID set. */
    val isSessionIdUnique: Boolean = false,
    /** Documented exact parent path from the session header. Internal metadata only; never displayed or logged. */
    val parentSessionPath: String? = null,
) {
    val hasStableIdentity: Boolean
        get() = sessionId.isValidPiSessionId() && isSessionIdUnique
}

@Serializable
data class SessionGroup(
    val cwd: String,
    val sessions: List<SessionRecord>,
)

@Serializable
data class CachedSessionIndex(
    val hostId: String,
    val cachedAtEpochMs: Long,
    val groups: List<SessionGroup>,
)

enum class SessionIndexSource {
    NONE,
    CACHE,
    REMOTE,
}

data class SessionIndexState(
    val hostId: String,
    val groups: List<SessionGroup> = emptyList(),
    val isRefreshing: Boolean = false,
    val source: SessionIndexSource = SessionIndexSource.NONE,
    val lastUpdatedEpochMs: Long? = null,
    val errorMessage: String? = null,
)

/** 会话文件间谱系的解析状态。未知/不可靠的关系绝不猜测。 */
enum class LineageStatus {
    NONE,
    LIVE,
    HISTORICAL,
    MISSING,
    AMBIGUOUS,
    CYCLE,
}

/** 单条会话的谱系投影：父会话的稳定内部 ID 与解析状态。 */
data class SessionLineage(
    val parentSessionId: String? = null,
    val status: LineageStatus = LineageStatus.NONE,
)
