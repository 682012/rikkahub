package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 缓存命中率口径：未知不当 0%，不加跨模型的分母，不靠 clamp 造 100%。 */
class CachedContextMetricsTest {
    @Test
    fun `models that never report cache are unknown not zero percent`() {
        val rate = computeCacheHitRate(
            listOf(
                CacheSample("proxy-model", promptTokens = 1000, cachedTokens = 0, requests = 5),
            ),
        )
        assertEquals(CacheHitRateState.UNKNOWN, rate.state)
        assertNull(rate.fraction)
        assertNull(rate.percentText)
        assertEquals(1000L, rate.excludedPromptTokens)
        assertEquals(5L, rate.excludedRequests)
        assertEquals(0L, rate.eligiblePromptTokens)
    }

    @Test
    fun `denominator only contains models that actually reported cache`() {
        // claude 报过命中，proxy 从不报缓存：比例只能用 claude 的 100 做分母。
        val rate = computeCacheHitRate(
            listOf(
                CacheSample("claude", promptTokens = 100, cachedTokens = 40, requests = 1),
                CacheSample("proxy", promptTokens = 900, cachedTokens = 0, requests = 9),
            ),
        )
        assertEquals(CacheHitRateState.REPORTED, rate.state)
        assertEquals(100L, rate.eligiblePromptTokens)
        assertEquals(900L, rate.excludedPromptTokens)
        assertEquals(0.4, rate.fraction!!, 1e-9)
        assertEquals("40.0%", rate.percentText)
        assertEquals(40L, rate.cachedTokens)
    }

    @Test
    fun `cache larger than input is invalid and never becomes a hundred percent`() {
        val rate = computeCacheHitRate(listOf(CacheSample("claude", promptTokens = 100, cachedTokens = 200)))
        assertEquals(CacheHitRateState.INVALID, rate.state)
        assertFalse(rate.hasRatio)
        assertNull(rate.percentText)
    }

    @Test
    fun `negative cache is invalid`() {
        val rate = computeCacheHitRate(listOf(CacheSample("openai", promptTokens = 100, cachedTokens = -5)))
        assertEquals(CacheHitRateState.INVALID, rate.state)
        assertNull(rate.fraction)
    }

    @Test
    fun `empty selection stays unknown`() {
        val rate = computeCacheHitRate(emptyList())
        assertEquals(CacheHitRateState.UNKNOWN, rate.state)
        assertNull(rate.fraction)
        assertEquals(0L, rate.cachedTokens)
    }

    @Test
    fun `legacy rows are judged one by one so a hit never pulls unknown input into the denominator`() {
        // 两条都没带覆盖度标志（旧数据 / 上层没查询覆盖度）：
        // 只有自己 cached > 0 的那条算已上报；cached = 0 的那条是未知，只能排除。
        // 以前按 modelKey 先合并再判断，等于把未知的 100 拖进分母，命中率被稀释成 12.5%。
        val rate = computeCacheHitRate(
            listOf(
                CacheSample("claude", promptTokens = 100, cachedTokens = 0, requests = 2),
                CacheSample("claude", promptTokens = 100, cachedTokens = 25, requests = 1),
            ),
        )
        assertEquals(CacheHitRateState.REPORTED, rate.state)
        assertEquals("未知那条不能进分母", 100L, rate.eligiblePromptTokens)
        assertEquals(25L, rate.eligibleCachedTokens)
        assertEquals(0.25, rate.fraction!!, 1e-9)
        assertEquals(100L, rate.excludedPromptTokens)
        assertEquals(2L, rate.excludedRequests)
    }

    @Test
    fun `invalid row hidden behind a big sibling prompt still invalidates the batch`() {
        // 聚合行里一条 cached > prompt 的坏行，被同组的大输入在 SUM 里盖住了：
        // 150/10000 看起来完全合法，所以必须靠逐行计数带上的 invalid 标记来作废整批。
        val rate = computeCacheHitRate(
            listOf(
                CacheSample(
                    modelKey = "provider-a|claude",
                    promptTokens = 10_000,
                    cachedTokens = 150,
                    requests = 10,
                    reported = true,
                    invalid = true,
                ),
            ),
        )
        assertEquals(CacheHitRateState.INVALID, rate.state)
        assertNull(rate.percentText)
        assertFalse(rate.hasRatio)
        assertEquals("原始数值照旧保留，不做 clamp", 150L, rate.cachedTokens)
    }
}
