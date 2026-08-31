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
        val byStableId =
            groups
                .flatMap { it.sessions }
                .filter { it.hasStableIdentity }
                .associateBy { it.sessionId!! }

        val validIdCounts =
            groups
                .flatMap { it.sessions }
                .mapNotNull { it.sessionId?.takeIf { id -> id.isValidPiSessionId() } }
                .groupingBy { it }
                .eachCount()

        // 路径 → ID 的映射只收录「唯一稳定 ID」的会话（与索引侧 normalizeStableIdentities 口径一致）
        val pathToSessionId =
            groups
                .flatMap { it.sessions }
                .filter { it.hasStableIdentity }
                .filter { validIdCounts[it.sessionId!!] == 1 }
                .groupBy { normalizePath(it.sessionPath) }
                .filterValues { it.size == 1 }
                .mapValues { it.value.single().sessionId!! }

        val lineageBySessionId = mutableMapOf<String, SessionLineage>()

        for (group in groups) {
            for (session in group.sessions) {
                val sessionId = session.sessionId?.takeIf { session.hasStableIdentity } ?: continue
                val parentPath = session.parentSessionPath?.takeIf { it.isNotBlank() } ?: continue

                val normalizedParent = normalizePath(parentPath)
                val normalizedSelf = normalizePath(session.sessionPath)

                if (normalizedParent == normalizedSelf) {
                    lineageBySessionId[sessionId] = SessionLineage(null, LineageStatus.CYCLE)
                    continue
                }

                val parentId = pathToSessionId[normalizedParent]
                lineageBySessionId[sessionId] =
                    if (parentId == null) {
                        SessionLineage(null, LineageStatus.MISSING)
                    } else {
                        SessionLineage(parentId, LineageStatus.LIVE)
                    }
            }
        }

        // 多节点环检测：沿 LIVE 边走，回到起点即整条链标记 CYCLE。
        for (start in lineageBySessionId.keys) {
            if (lineageBySessionId[start]?.status != LineageStatus.LIVE) continue
            var current = start
            val chain = mutableListOf(start)
            val seen = mutableSetOf(start)
            while (true) {
                val next = lineageBySessionId[current]?.parentSessionId ?: break
                if (next == start) {
                    chain.forEach { id ->
                        lineageBySessionId[id] = SessionLineage(lineageBySessionId[id]?.parentSessionId, LineageStatus.CYCLE)
                    }
                    break
                }
                if (!seen.add(next) || lineageBySessionId[next]?.status != LineageStatus.LIVE) break
                chain.add(next)
                current = next
            }
        }

        return lineageBySessionId
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/').trimEnd('/')
}
