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
    @Test fun totalsUseLongAndBadCacheCannotExceedInput() {
        val rows = List(3) { group("CHAT", Int.MAX_VALUE.toLong(), 100, Long.MAX_VALUE) }
        val result = summarizeConversationUsage(rows, UsageScope.ALL)
        assertEquals(3L * Int.MAX_VALUE, result.totals.prompt)
        assertEquals(result.totals.prompt, result.totals.cached)
    }
    @Test fun emptyScopeIsNeutral() {
        val result = summarizeConversationUsage(emptyList(), UsageScope.SUBAGENT)
        assertEquals(0L, result.totals.requests)
        assertTrue(result.models.isEmpty())
    }
}
