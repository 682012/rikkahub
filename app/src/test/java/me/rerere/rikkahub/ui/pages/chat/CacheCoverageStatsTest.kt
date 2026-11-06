package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.db.dao.ConversationUsageGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖度口径的确定性用例（纯 JVM）：
 * - 同一模型也要分"报过 0"和"没报字段"，不能因为模型曾经报过缓存就把缺失 usage 的请求也算进分母；
 * - 缓存写入（cache_creation）是输入但不是命中，绝不双算；
 * - 命中率分子分母成对来自同一 (source, provider, model) 的同一批记录；
 * - 上游异常（cached > prompt）原样保留，不给比例。
 */
class CacheCoverageStatsTest {
    private fun group(
        source: String = "CHAT",
        providerId: String = "provider-a",
        modelId: String = "claude-sonnet",
        modelName: String = "claude-sonnet",
        prompt: Long,
        cached: Long,
        requests: Long = 1,
        knownPrompt: Long = 0,
        knownCached: Long = 0,
        knownRequests: Long = 0,
        unknownPrompt: Long = 0,
        unknownRequests: Long = 0,
        creation: Long = 0,
    ) = ConversationUsageGroup(
        source = source,
        modelId = modelId,
        modelName = modelName,
        requests = requests,
        promptTokens = prompt,
        completionTokens = 0,
        cachedTokens = cached,
        providerId = providerId,
        cacheCreationTokens = creation,
        knownPromptTokens = knownPrompt,
        knownCachedTokens = knownCached,
        knownRequests = knownRequests,
        unknownPromptTokens = unknownPrompt,
        unknownRequests = unknownRequests,
    )

    @Test
    fun `explicit zero hit is a real zero percent while unknown rows are excluded`() {
        val summary = summarizeConversationUsage(
            listOf(
                group(
                    prompt = 1000,
                    cached = 0,
                    requests = 3,
                    knownPrompt = 400,
                    knownCached = 0,
                    knownRequests = 1,
                    unknownPrompt = 600,
                    unknownRequests = 2,
                ),
            ),
        )

        val cache = summary.cache
        assertEquals(CacheHitRateState.REPORTED, cache.state)
        assertEquals("上报了 0 命中就是真 0%，不是未知", 0.0, cache.fraction!!, 1e-9)
        assertEquals(400L, cache.eligiblePromptTokens)
        assertEquals(600L, cache.excludedPromptTokens)
        assertEquals(2L, cache.excludedRequests)
        assertEquals(400L, cache.zeroHitPromptTokens)
    }

    @Test
    fun `known positive and unknown in the same model use paired numerator and denominator`() {
        val summary = summarizeConversationUsage(
            listOf(
                group(
                    prompt = 1000,
                    cached = 120,
                    requests = 3,
                    knownPrompt = 400,
                    knownCached = 120,
                    knownRequests = 1,
                    unknownPrompt = 600,
                    unknownRequests = 2,
                ),
            ),
        )

        assertEquals(0.3, summary.cache.fraction!!, 1e-9)
        assertEquals("未知的 600 输入绝不能进分母", 400L, summary.cache.eligiblePromptTokens)
        assertEquals(120L, summary.cache.cachedTokens)
    }

    @Test
    fun `legacy rows without coverage are inferred from cached positive and never become zero percent`() {
        val legacyHit = summarizeConversationUsage(listOf(group(prompt = 200, cached = 50, requests = 2)))
        assertEquals(CacheHitRateState.REPORTED, legacyHit.cache.state)
        assertEquals(0.25, legacyHit.cache.fraction!!, 1e-9)

        // 旧数据里 cached = 0，历史上"可能是没报字段"，按未知处理，不造 0%
        val legacyZero = summarizeConversationUsage(listOf(group(prompt = 900, cached = 0, requests = 9)))
        assertEquals(CacheHitRateState.UNKNOWN, legacyZero.cache.state)
        assertNull(legacyZero.cache.percentText)
        assertEquals(900L, legacyZero.cache.excludedPromptTokens)
    }

    @Test
    fun `same model id on two providers is never cross paired`() {
        val summary = summarizeConversationUsage(
            listOf(
                // 只报过命中的渠道：1000 输入里命中 400
                group(providerId = "provider-a", prompt = 400, cached = 200, knownPrompt = 400, knownCached = 200, knownRequests = 1),
                // 同一 modelId 的转发层：从不报缓存字段
                group(providerId = "provider-b", prompt = 600, cached = 0, unknownPrompt = 600, unknownRequests = 6),
            ),
        )

        assertEquals(0.5, summary.cache.fraction!!, 1e-9)
        assertEquals(400L, summary.cache.eligiblePromptTokens)
        assertEquals(600L, summary.cache.excludedPromptTokens)
    }

    @Test
    fun `cache creation is input but never a cache hit`() {
        val summary = summarizeConversationUsage(
            listOf(
                // Claude：input 100 + cache_read 0 + cache_creation 200
                group(
                    prompt = 300,
                    cached = 0,
                    knownPrompt = 300,
                    knownCached = 0,
                    knownRequests = 1,
                    creation = 200,
                ),
            ),
        )

        assertEquals("cache_creation 不算命中", 0.0, summary.cache.fraction!!, 1e-9)
        assertEquals(300L, summary.totals.prompt)
        assertEquals(0L, summary.totals.cached)
        assertEquals("合计 token 仍只按 prompt + completion", 300L, summary.totals.prompt + summary.totals.output)
        assertEquals(200L, summary.bySource.single().cacheCreation)
    }

    @Test
    fun `unknown aggregation keeps excluded input visible`() {
        val summary = summarizeConversationUsage(
            listOf(
                group(providerId = "p1", modelId = "m1", prompt = 500, cached = 0, unknownPrompt = 500, unknownRequests = 5),
                group(providerId = "p2", modelId = "m2", prompt = 700, cached = 0, unknownPrompt = 700, unknownRequests = 7),
            ),
        )

        assertEquals(CacheHitRateState.UNKNOWN, summary.cache.state)
        assertFalse(summary.cache.hasRatio)
        assertEquals(1200L, summary.cache.excludedPromptTokens)
        assertEquals(12L, summary.cache.excludedRequests)
        assertEquals(1200L, summary.totals.prompt)
    }

    @Test
    fun `cached larger than prompt is reported raw without a ratio`() {
        val summary = summarizeConversationUsage(
            listOf(group(prompt = 100, cached = 250, knownPrompt = 100, knownCached = 250, knownRequests = 1)),
        )

        assertTrue(summary.totals.invalidCacheReport)
        assertEquals(CacheHitRateState.INVALID, summary.cache.state)
        assertNull(summary.cache.percentText)
        assertEquals("异常值原样保留，不 clamp", 250L, summary.totals.cached)
        assertEquals(100L, summary.totals.prompt)
    }

    @Test
    fun `invalid row flattened by a big sibling in the same group still gives no ratio`() {
        // 组内一条 cached > prompt 的坏行被大输入盖住（合计 150/10000 看起来合法）：
        // DAO 的 invalidCacheReports 逐行计数把它带上来，汇总必须整批 INVALID，不能算出 1.5%。
        val summary = summarizeConversationUsage(
            listOf(
                group(
                    prompt = 10_000,
                    cached = 150,
                    requests = 10,
                    knownPrompt = 10_000,
                    knownCached = 150,
                    knownRequests = 10,
                ).copy(invalidCacheReports = 1),
            ),
        )

        assertTrue(summary.totals.invalidCacheReport)
        assertEquals(CacheHitRateState.INVALID, summary.cache.state)
        assertNull(summary.cache.percentText)
        assertEquals("异常值原样保留，不 clamp", 150L, summary.totals.cached)
        assertEquals(10_000L, summary.totals.prompt)
    }

    @Test
    fun `invalid flag does not leak across scopes`() {
        // 坏行只出现在 SUBAGENT，默认主对话口径不该被它污染。
        val summary = summarizeConversationUsage(
            listOf(
                group(prompt = 400, cached = 100, knownPrompt = 400, knownCached = 100, knownRequests = 1),
                group(source = "SUBAGENT", prompt = 800, cached = 900, knownPrompt = 800, knownCached = 900, knownRequests = 1)
                    .copy(invalidCacheReports = 1),
            ),
        )

        assertFalse(summary.totals.invalidCacheReport)
        assertEquals(0.25, summary.cache.fraction!!, 1e-9)
        val all = summarizeConversationUsage(
            listOf(
                group(prompt = 400, cached = 100, knownPrompt = 400, knownCached = 100, knownRequests = 1),
                group(source = "SUBAGENT", prompt = 800, cached = 900, knownPrompt = 800, knownCached = 900, knownRequests = 1)
                    .copy(invalidCacheReports = 1),
            ),
            scope = UsageScope.ALL,
        )
        assertTrue("显式选全部时坏行必须作废整批", all.totals.invalidCacheReport)
        assertEquals(CacheHitRateState.INVALID, all.cache.state)
    }

    @Test
    fun `subagent and auxiliary sources stay out of the default chat scope`() {
        val summary = summarizeConversationUsage(
            listOf(
                group(prompt = 100, cached = 50, knownPrompt = 100, knownCached = 50, knownRequests = 1),
                group(source = "SUBAGENT", prompt = 900, cached = 0, unknownPrompt = 900, unknownRequests = 9),
                group(source = "TITLE", prompt = 50, cached = 0, unknownPrompt = 50, unknownRequests = 1),
            ),
        )

        assertEquals(100L, summary.totals.prompt)
        assertEquals(0.5, summary.cache.fraction!!, 1e-9)
        assertEquals(0L, summary.cache.excludedPromptTokens)
        assertEquals("子代理和辅助要单列，默认不进主对话合计", 3, summary.bySource.size)
    }
}
