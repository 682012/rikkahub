package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.db.dao.ConversationUsageGroup

/**
 * 单个来源（CHAT / SUBAGENT / TITLE / COMPRESSION / ...）的用量，始终单列。
 *
 * 子代理的 conversation_id 是父会话 id，标题/摘要/快速模型也用父会话 id 记账，
 * 所以"本对话"这张表里天然混着三种来源。这里保留原始 source，让界面能逐项列出，
 * 而不是把它们悄悄加进主对话数字。
 */
data class ConversationSourceUsage(
    val source: String,
    val role: UsageSourceRole,
    val requests: Long,
    val prompt: Long,
    val output: Long,
    /** 命中读缓存的输入（是 [prompt] 的子集，不再重复计入合计）。 */
    val cached: Long,
    /** 新写入缓存的输入（Claude cache_creation），已含在 [prompt] 里，不是命中。 */
    val cacheCreation: Long = 0,
)

internal data class ConversationUsageSummary(
    val totals: DashboardTotals,
    val models: List<DashboardModel>,
    /** 只对当前筛选范围算的命中率；不同 source 绝不合并进同一个分子分母。 */
    val cache: CacheHitRate,
    /** 全部来源的逐项用量（不受 scope 影响），用于"子代理/辅助单列"。 */
    val bySource: List<ConversationSourceUsage>,
)

/**
 * 按来源范围汇总一次对话的用量。
 *
 * [scope] 默认 [UsageScope.CHAT]：主对话界面只显示主对话的数字。子代理与辅助请求
 * 必须由用户显式切到对应范围（或"全部"）才会被累计，绝不默认混入。
 */
internal fun summarizeConversationUsage(
    groups: List<ConversationUsageGroup>,
    scope: UsageScope = UsageScope.CHAT,
    modelNames: Map<String, String> = emptyMap(),
): ConversationUsageSummary {
    val selected = groups.filter { scope.includes(it.source) }
    val totals = DashboardTotals(
        requests = selected.sumOf { it.requests.coerceAtLeast(0) },
        prompt = selected.sumOf { it.promptTokens.coerceAtLeast(0) },
        output = selected.sumOf { it.completionTokens.coerceAtLeast(0) },
        cached = selected.sumOf { it.cachedTokens },
        invalidCacheReport = selected.any {
            it.invalidCacheReports > 0 || it.cachedTokens < 0 || it.cachedTokens > it.promptTokens
        },
        cache = computeCacheHitRate(selected.flatMap { group -> group.toCacheSamples() }),
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
    val bySource = groups.groupBy { normalizeUsageSource(it.source) }
        .map { (source, rows) ->
            ConversationSourceUsage(
                source = source,
                role = roleOfUsageSource(source),
                requests = rows.sumOf { it.requests.coerceAtLeast(0) },
                prompt = rows.sumOf { it.promptTokens.coerceAtLeast(0) },
                output = rows.sumOf { it.completionTokens.coerceAtLeast(0) },
                cached = rows.sumOf { it.cachedTokens },
                cacheCreation = rows.sumOf { it.cacheCreationTokens },
            )
        }
        .sortedWith(
            compareBy<ConversationSourceUsage> { it.role.ordinal }
                .thenByDescending { it.prompt + it.output },
        )
    return ConversationUsageSummary(totals, models, totals.cache, bySource)
}

/** [UsageScope] 是否包含某个 usage_record.source（大小写不敏感，历史数据有小写）。 */
internal fun UsageScope.includes(source: String): Boolean {
    val normalized = normalizeUsageSource(source)
    return when (this) {
        UsageScope.ALL -> true
        UsageScope.CHAT -> normalized == USAGE_SOURCE_CHAT
        UsageScope.SUBAGENT -> normalized == USAGE_SOURCE_SUBAGENT
        UsageScope.OTHER -> normalized != USAGE_SOURCE_CHAT && normalized != USAGE_SOURCE_SUBAGENT
    }
}

/**
 * 把一个 (source, provider, model) 汇总行拆成两条缓存样本：**明确上报过的**和**没报字段的**。
 *
 * 拆开的意义：同一模型也可能一次报了 0 命中、另一次压根没给缓存字段。
 * 前者是真实 0%，必须留在分母里；后者是未知，只能排除并单独计数。
 * 命中量只从已上报的那条里出，绝不拿整个模型的 cached 去配部分 prompt。
 *
 * 组内一旦有上游异常行（[ConversationUsageGroup.invalidCacheReports] > 0），整组的样本都带上
 * [CacheSample.invalid]，因为那条坏行很可能已经被同组的大输入在 SUM 里盖住，只有标记还留着。
 */
private fun ConversationUsageGroup.toCacheSamples(): List<CacheSample> {
    val key = "${providerId}|${modelId?.takeIf(String::isNotBlank) ?: modelName.orEmpty()}"
    val bad = invalidCacheReports > 0
    val hasCoverage = knownRequests > 0 || unknownRequests > 0 ||
        knownPromptTokens > 0 || unknownPromptTokens > 0 || knownCachedTokens > 0
    if (!hasCoverage) {
        // 没有覆盖度信息（旧数据被上层直接构造，或还没迁移的缓存）：退回按 `cached > 0` 推断，
        // 由 computeCacheHitRate 统一处理，绝不当成"上报了 0"。
        return listOf(
            CacheSample(
                modelKey = key,
                promptTokens = promptTokens,
                cachedTokens = cachedTokens,
                requests = requests,
                reported = null,
                invalid = bad,
            ),
        )
    }
    return buildList {
        if (knownRequests > 0 || knownPromptTokens > 0 || knownCachedTokens > 0) {
            add(
                CacheSample(
                    modelKey = key,
                    promptTokens = knownPromptTokens,
                    cachedTokens = knownCachedTokens,
                    requests = knownRequests,
                    reported = true,
                    invalid = bad,
                ),
            )
        }
        if (unknownRequests > 0 || unknownPromptTokens > 0) {
            add(
                CacheSample(
                    modelKey = key,
                    promptTokens = unknownPromptTokens,
                    cachedTokens = 0,
                    requests = unknownRequests,
                    reported = false,
                    invalid = bad,
                ),
            )
        }
    }
}
