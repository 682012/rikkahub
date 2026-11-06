package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.db.dao.ConversationUsageGroup
import org.junit.Assert.*
import org.junit.Test

class ConversationUsageSummaryTest {
    private fun group(source: String, input: Long, output: Long, cache: Long = 0, id: String? = "id", name: String? = "model") =
        ConversationUsageGroup(source, id, name, 1, input, output, cache)

    @Test fun cacheIsASubsetNotAnExtraCharge() {
        val result = summarizeConversationUsage(listOf(group("CHAT", 100, 20, 80)), UsageScope.ALL)
        assertEquals(100L, result.totals.prompt)
        assertEquals(20L, result.totals.output)
        assertEquals(120L, result.models.single().tokens)
        assertEquals(80L, result.totals.cached)
    }
    @Test fun scopesPartitionRequestsWithoutDoubleCounting() {
        val rows = listOf(group("chat", 100, 20), group("SUBAGENT", 300, 30), group("COMPRESSION", 200, 10))
        assertEquals(3L, summarizeConversationUsage(rows, UsageScope.ALL).totals.requests)
        assertEquals(100L, summarizeConversationUsage(rows, UsageScope.CHAT).totals.prompt)
        assertEquals(300L, summarizeConversationUsage(rows, UsageScope.SUBAGENT).totals.prompt)
        assertEquals(200L, summarizeConversationUsage(rows, UsageScope.OTHER).totals.prompt)
    }
    @Test fun modelDisplayNameWinsAndSnapshotSurvivesDeletion() {
        val row = group("CHAT", 100, 20)
        assertEquals("Display", summarizeConversationUsage(listOf(row), UsageScope.ALL, mapOf("id" to "Display")).models.single().name)
        assertEquals("model", summarizeConversationUsage(listOf(row), UsageScope.ALL).models.single().name)
        assertEquals("已移除的模型", summarizeConversationUsage(listOf(row.copy(modelName = null)), UsageScope.ALL).models.single().name)
    }
    @Test fun totalsUseLongAndBadUpstreamCacheIsNotSilentlyRewritten() {
        val rows = List(3) { group("CHAT", Int.MAX_VALUE.toLong(), 100, 2L * Int.MAX_VALUE) }
        val result = summarizeConversationUsage(rows, UsageScope.ALL)
        assertEquals(3L * Int.MAX_VALUE, result.totals.prompt)
        assertEquals(6L * Int.MAX_VALUE, result.totals.cached)
        assertTrue(result.totals.invalidCacheReport)
    }
    @Test fun defaultScopeIsMainChatAndSourceRowsStaySeparate() {
        // 子代理/标题和主对话共用 conversation_id：默认合计必须是主对话，不能把它们混进来
        val rows = listOf(group("CHAT", 100, 20), group("SUBAGENT", 300, 30), group("TITLE", 200, 10))
        val result = summarizeConversationUsage(rows)
        assertEquals(100L, result.totals.prompt)
        assertEquals(1L, result.totals.requests)
        assertEquals(3, result.bySource.size)
        assertEquals(UsageSourceRole.SUBAGENT, result.bySource.single { it.source == "SUBAGENT" }.role)
        assertEquals(UsageSourceRole.AUXILIARY, result.bySource.single { it.source == "TITLE" }.role)
        assertEquals(600L, result.bySource.sumOf { it.prompt })
        // 只有显式选"全部"才累计
        assertEquals(600L, summarizeConversationUsage(rows, UsageScope.ALL).totals.prompt)
    }
    @Test fun cacheRateStaysInsideOneSourceAndSkipsModelsWithoutReports() {
        val rows = listOf(
            group("CHAT", 100, 20, 40, id = "claude", name = "claude"),
            group("CHAT", 900, 20, 0, id = "proxy", name = "proxy"),
            group("SUBAGENT", 500, 5, 500, id = "claude2", name = "claude2"),
        )
        val chat = summarizeConversationUsage(rows).totals.cache
        assertEquals(100L, chat.eligiblePromptTokens)
        assertEquals(900L, chat.excludedPromptTokens)
        assertEquals(0.4, chat.fraction!!, 1e-9)
        val subagent = summarizeConversationUsage(rows, UsageScope.SUBAGENT).totals.cache
        assertEquals(500L, subagent.eligiblePromptTokens)
        assertEquals(1.0, subagent.fraction!!, 1e-9)
        // 混算会给出 540/1500 这种跨来源比例，这里必须不成立
        assertNotEquals(540.0 / 1500.0, chat.fraction)
    }
    @Test fun unreportedCacheIsUnknownNotZeroPercent() {
        val result = summarizeConversationUsage(listOf(group("CHAT", 1000, 10, 0)))
        assertEquals(0L, result.totals.cached)
        assertFalse(result.totals.cache.hasRatio)
        assertEquals(CacheHitRateState.UNKNOWN, result.totals.cache.state)
    }
    @Test fun legacyOrBrokenRowsKeepRawValuesButGiveNoRatio() {
        val removed = group("CHAT", 100, 20, 0, id = null, name = null)
        assertEquals("已移除的模型", summarizeConversationUsage(listOf(removed)).models.single().name)
        val broken = summarizeConversationUsage(listOf(group("CHAT", 100, 20, 150, id = "m", name = "m")))
        assertTrue(broken.totals.invalidCacheReport)
        assertEquals(CacheHitRateState.INVALID, broken.totals.cache.state)
        assertNull(broken.totals.cache.percentText)
    }
    @Test fun emptyScopeIsNeutral() {
        val result = summarizeConversationUsage(emptyList(), UsageScope.SUBAGENT)
        assertEquals(0L, result.totals.requests)
        assertTrue(result.models.isEmpty())
    }
}
