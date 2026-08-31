package top.guozk.pipilot.coresessions

/**
 * 会话文件间谱系解析（继承原计划 015 的判定规则）：
 *
 * - 只依据文档化的头部字段 parentSession 精确路径解析，绝不从文件名、cwd、
 *   时间戳或标题猜测亲缘关系。
 * - 路径精确匹配到根内唯一稳定 ID 的会话 → LIVE。
 * - 自引用与多节点环标记为 CYCLE，不影响其他会话。
 * - parentSessionPath 属于认证/缓存内部元数据，不进入 URL 或日志。
 */
object SessionLineageResolver {
    fun resolve(groups: List<SessionGroup>): Map<String, SessionLineage> {
        val validIdCounts =
            groups
                .flatMap { it.sessions }
                .mapNotNull { it.sessionId?.takeIf { id -> id.isValidPiSessionId() } }
                .groupingBy { it }
                .eachCount()

        // 路径 → ID 的映射只收录「唯一稳定 ID」的会话（与索引侧 normalizeStableIdentities 口径一致）
        val pathToSessionId = buildPathToSessionId(groups, validIdCounts)

        val lineageBySessionId = buildInitialLineage(groups, pathToSessionId)
        markCycles(lineageBySessionId)
        return lineageBySessionId
    }

    private fun buildPathToSessionId(
        groups: List<SessionGroup>,
        validIdCounts: Map<String, Int>,
    ): Map<String, String> =
        groups
            .flatMap { it.sessions }
            .filter { it.hasStableIdentity }
            .filter { validIdCounts[it.sessionId!!] == 1 }
            .groupBy { normalizePath(it.sessionPath) }
            .filterValues { it.size == 1 }
            .mapValues { it.value.single().sessionId!! }

    private fun buildInitialLineage(
        groups: List<SessionGroup>,
        pathToSessionId: Map<String, String>,
    ): MutableMap<String, SessionLineage> {
        val lineageBySessionId = mutableMapOf<String, SessionLineage>()

        groups
            .flatMap { it.sessions }
            .filter { it.hasStableIdentity && !it.parentSessionPath.isNullOrBlank() }
            .forEach { session ->
                val sessionId = session.sessionId!!
                val normalizedParent = normalizePath(session.parentSessionPath!!)
                lineageBySessionId[sessionId] = resolveParent(session.sessionPath, normalizedParent, pathToSessionId)
            }
        return lineageBySessionId
    }

    private fun resolveParent(
        selfPath: String,
        normalizedParent: String,
        pathToSessionId: Map<String, String>,
    ): SessionLineage =
        if (normalizedParent == normalizePath(selfPath)) {
            SessionLineage(null, LineageStatus.CYCLE)
        } else {
            pathToSessionId[normalizedParent]
                ?.let { SessionLineage(it, LineageStatus.LIVE) }
                ?: SessionLineage(null, LineageStatus.MISSING)
        }

    /** 多节点环检测：沿 LIVE 边走，回到起点即整条链标记 CYCLE。 */
    private fun markCycles(lineageBySessionId: MutableMap<String, SessionLineage>) {
        for (start in lineageBySessionId.keys) {
            val chain = walkChain(start, lineageBySessionId) ?: continue
            chain.forEach { id ->
                val parent = lineageBySessionId[id]?.parentSessionId
                lineageBySessionId[id] = SessionLineage(parent, LineageStatus.CYCLE)
            }
        }
    }

    /** 从 start 沿 LIVE 边走；回到 start 返回整条链，否则返回 null。 */
    private fun walkChain(
        start: String,
        lineageBySessionId: Map<String, SessionLineage>,
    ): List<String>? {
        if (lineageBySessionId[start]?.status != LineageStatus.LIVE) return null

        var current: String? = start
        val chain = mutableListOf(start)
        val seen = mutableSetOf(start)
        var cycleClosed = false
        var traversing = true

        while (traversing) {
            val next = lineageBySessionId[current]?.parentSessionId
            when {
                next == null -> traversing = false
                next == start -> {
                    cycleClosed = true
                    traversing = false
                }
                seen.add(next) && lineageBySessionId[next]?.status == LineageStatus.LIVE -> {
                    chain.add(next)
                    current = next
                }
                else -> traversing = false
            }
        }
        return if (cycleClosed) chain else null
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/').trimEnd('/')
}
