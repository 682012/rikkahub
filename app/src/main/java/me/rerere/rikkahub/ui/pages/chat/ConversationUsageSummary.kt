package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.db.dao.ConversationUsageGroup

internal data class ConversationUsageSummary(
    val totals: DashboardTotals,
    val models: List<DashboardModel>,
)

internal fun summarizeConversationUsage(
    groups: List<ConversationUsageGroup>,
    scope: UsageScope,
    modelNames: Map<String, String> = emptyMap(),
): ConversationUsageSummary {
    val selected = groups.filter {
        when (scope) {
            UsageScope.ALL -> true
            UsageScope.CHAT -> it.source.equals("CHAT", true)
            UsageScope.SUBAGENT -> it.source.equals("SUBAGENT", true)
            UsageScope.OTHER -> !it.source.equals("CHAT", true) && !it.source.equals("SUBAGENT", true)
        }
    }
    val totals = DashboardTotals(
        requests = selected.sumOf { it.requests.coerceAtLeast(0) },
        prompt = selected.sumOf { it.promptTokens.coerceAtLeast(0) },
        output = selected.sumOf { it.completionTokens.coerceAtLeast(0) },
        cached = selected.sumOf { it.cachedTokens },
        invalidCacheReport = selected.any { it.cachedTokens < 0 || it.cachedTokens > it.promptTokens },
    )
    val models = selected.groupBy { it.modelId?.takeIf(String::isNotBlank) ?: it.modelName.orEmpty() }
        .map { (id, rows) ->
            DashboardModel(
                name = modelNames[id]?.takeIf(String::isNotBlank)
                    ?: rows.firstNotNullOfOrNull { it.modelName?.takeIf(String::isNotBlank) }
                    ?: "已移除的模型",
                requests = rows.sumOf { it.requests.coerceAtLeast(0) },
                tokens = rows.sumOf { it.promptTokens.coerceAtLeast(0) + it.completionTokens.coerceAtLeast(0) },
            )
        }.sortedByDescending { it.tokens }
    return ConversationUsageSummary(totals, models)
}
