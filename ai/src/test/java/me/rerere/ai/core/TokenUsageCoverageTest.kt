package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 缓存上报覆盖度的传播：merge 不能被"另一个快照的缺省 0"抹掉，sum 不能把部分覆盖说成完整覆盖。
 */
class TokenUsageCoverageTest {
    private val reportedZero = TokenUsage(promptTokens = 100, completionTokens = 1, cachedTokens = 0, cachedReported = true)
    private val unknown = TokenUsage(promptTokens = 200, completionTokens = 1)
    private val hit = TokenUsage(promptTokens = 300, completionTokens = 1, cachedTokens = 120, cachedReported = true)

    @Test
    fun `explicit zero stays reported through merge`() {
        val merged = unknown.merge(reportedZero)
        assertEquals(true, merged.cachedReported)
        assertEquals(0, merged.cachedTokens)
    }

    @Test
    fun `snapshot without cache field does not downgrade a known value`() {
        val merged = hit.merge(TokenUsage(promptTokens = 400, completionTokens = 2))
        assertEquals(true, merged.cachedReported)
        assertEquals(120, merged.cachedTokens)
    }

    @Test
    fun `unknown stays unknown through merge`() {
        val merged: TokenUsage? = null
        assertNull(merged.merge(unknown).cachedReported)
    }

    @Test
    fun `sum of two reported requests stays reported`() {
        val total = hit.sum(reportedZero)!!
        assertEquals(true, total.cachedReported)
        assertEquals(120, total.cachedTokens)
        assertEquals(400, total.promptTokens)
    }

    @Test
    fun `sum with one unknown request is partial and must not claim completeness`() {
        val total = hit.sum(unknown)!!
        assertNull("混了未知请求就不能说已完整上报", total.cachedReported)
        assertEquals(120, total.cachedTokens)
    }

    @Test
    fun `cache creation is only summed when every request reported it`() {
        val claudeA = TokenUsage(promptTokens = 300, completionTokens = 1, cachedTokens = 100, cachedReported = true, cacheCreationTokens = 200)
        val claudeB = TokenUsage(promptTokens = 150, completionTokens = 1, cachedTokens = 0, cachedReported = true, cacheCreationTokens = 50)
        val total = claudeA.sum(claudeB)!!
        assertEquals("两次都报了写缓存量才能相加", 250, total.cacheCreationTokens)
        assertEquals("命中只算 cache_read，绝不把写缓存算进来", 100, total.cachedTokens)
        assertEquals(450, total.promptTokens)

        val legacy = TokenUsage(promptTokens = 10, completionTokens = 1, cachedTokens = 0, cachedReported = true)
        assertNull("有一次不知道写缓存量，合计就不能冒充总量", claudeA.sum(legacy)!!.cacheCreationTokens)
    }

    @Test
    fun `merge keeps a negative upstream value instead of clamping it away`() {
        val broken = TokenUsage(promptTokens = 100, completionTokens = 1, cachedTokens = -5, cachedReported = true)
        assertEquals("merge 不许把上游负数抹成 0，否则下游永远看不到异常", -5, unknown.merge(broken).cachedTokens)
        assertEquals(true, unknown.merge(broken).cachedReported)
        val overReported = TokenUsage(promptTokens = 100, completionTokens = 1, cachedTokens = 900, cachedReported = true)
        assertEquals(900, hit.merge(overReported).cachedTokens)
    }
}
