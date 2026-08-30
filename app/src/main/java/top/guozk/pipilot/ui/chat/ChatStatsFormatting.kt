package top.guozk.pipilot.ui.chat

import top.guozk.pipilot.corerpc.SessionStats
import top.guozk.pipilot.sessions.ModelInfo

@Suppress("MagicNumber")
internal fun formatNumber(value: Long): String {
    return when {
        value >= 1_000_000 -> String.format(java.util.Locale.US, "%.2fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}

internal fun formatContextUsageLabel(
    stats: SessionStats?,
    currentModel: ModelInfo?,
): String {
    val statsSnapshot = stats ?: return "上下文 --"

    val explicitUsedTokens = statsSnapshot.contextUsedTokens?.coerceAtLeast(0L)
    val explicitWindowTokens = statsSnapshot.contextWindowTokens?.takeIf { it > 0 }
    val explicitPercent = statsSnapshot.contextUsagePercent?.coerceIn(CONTEXT_PERCENT_MIN, CONTEXT_PERCENT_MAX)
    val fallbackUsedTokens = (statsSnapshot.inputTokens + statsSnapshot.outputTokens).coerceAtLeast(0L)
    val fallbackWindowTokens = currentModel?.contextWindow?.takeIf { it > 0 }?.toLong()

    val contextUsage =
        buildContextUsageCoreLabel(
            explicitUsedTokens = explicitUsedTokens,
            explicitWindowTokens = explicitWindowTokens,
            explicitPercent = explicitPercent,
            fallbackUsedTokens = fallbackUsedTokens,
            fallbackWindowTokens = explicitWindowTokens ?: fallbackWindowTokens,
        )
    val compactionLabel =
        statsSnapshot.compactionCount.takeIf { it > 0 }?.let { count -> " · C$count" }.orEmpty()
    val costLabel =
        statsSnapshot.totalCost.takeIf { it > 0.0 }?.let { cost -> " · ${formatCompactCost(cost)}" }.orEmpty()

    return contextUsage + compactionLabel + costLabel
}

private fun buildContextUsageCoreLabel(
    explicitUsedTokens: Long?,
    explicitWindowTokens: Long?,
    explicitPercent: Int?,
    fallbackUsedTokens: Long,
    fallbackWindowTokens: Long?,
): String {
    return when {
        explicitPercent != null && explicitUsedTokens != null && explicitWindowTokens != null ->
            formatExactContextUsage(explicitPercent, explicitUsedTokens, explicitWindowTokens)
        explicitPercent != null -> "上下文 $explicitPercent%"
        explicitUsedTokens != null && explicitWindowTokens != null ->
            formatExactContextUsage(
                computeContextPercent(explicitUsedTokens, explicitWindowTokens),
                explicitUsedTokens,
                explicitWindowTokens,
            )
        explicitUsedTokens != null -> "上下文 ${formatNumber(explicitUsedTokens)}"
        fallbackWindowTokens != null ->
            "上下文 ~${formatNumber(fallbackUsedTokens)}/${formatNumber(fallbackWindowTokens)}"
        else -> "上下文 ~${formatNumber(fallbackUsedTokens)}"
    }
}

private fun computeContextPercent(
    usedTokens: Long,
    windowTokens: Long,
): Int {
    return ((usedTokens * CONTEXT_PERCENT_FACTOR) / windowTokens.toDouble())
        .toInt()
        .coerceIn(CONTEXT_PERCENT_MIN, CONTEXT_PERCENT_MAX)
}

private fun formatExactContextUsage(
    percent: Int,
    usedTokens: Long,
    windowTokens: Long,
): String {
    return "上下文 $percent% · ${formatNumber(usedTokens)}/${formatNumber(windowTokens)}"
}

@Suppress("MagicNumber")
internal fun formatCost(value: Double): String {
    return String.format(java.util.Locale.US, "$%.4f", value)
}

@Suppress("MagicNumber")
private fun formatCompactCost(value: Double): String {
    val pattern =
        when {
            value >= 1.0 -> "$%.2f"
            value >= 0.1 -> "$%.3f"
            else -> "$%.4f"
        }
    return String.format(java.util.Locale.US, pattern, value)
}

private const val CONTEXT_PERCENT_FACTOR = 100.0
private const val CONTEXT_PERCENT_MIN = 0
private const val CONTEXT_PERCENT_MAX = 100
