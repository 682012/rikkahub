package me.rerere.ai.agent

import kotlinx.serialization.json.Json
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 用户自己加的「额外重试类型」：保存后真的改变分类，但绝不重放已发生的写操作。
 *
 * 全部确定性：不 sleep、不联网、不猜错误文本里的状态码。
 */
class CustomRetryMatchTest {

    private fun rule(
        pattern: String,
        mode: CustomRetryMatchMode = CustomRetryMatchMode.LITERAL,
        enabled: Boolean = true,
        id: String = "r1",
    ) = CustomRetryRule(id = id, pattern = pattern, mode = mode, enabled = enabled)

    private val vendorError = IllegalStateException("vendor said: upstream tunnel hiccup 502")

    private fun customScope(rules: List<CustomRetryRule>, safe: Boolean = true) = RetryScope(
        mode = RetryScopeMode.CUSTOM,
        categories = setOf(RetryErrorCategory.CUSTOM_MATCH),
        customRules = rules,
        safeToReplay = safe,
    )

    // ---- 分类真的被规则改变：这是"保存后影响分类"的最小证据 ----------------

    @Test
    fun `without rules a custom vendor error is not retried`() {
        val scope = RetryScope(mode = RetryScopeMode.CUSTOM, categories = emptySet())
        assertFalse(scope.allows(vendorError))
    }

    @Test
    fun `a saved literal rule makes the matching error retryable`() {
        val scope = customScope(listOf(rule("tunnel hiccup")))
        assertTrue(scope.allows(vendorError))
        assertEquals(RetryErrorCategory.CUSTOM_MATCH, scope.matchedCategory(vendorError))
        // 不相关的错误仍然不重试：规则没有变成"重试一切"
        assertFalse(scope.allows(IllegalStateException("something else entirely")))
    }

    @Test
    fun `deleting the rule puts the classification back`() {
        val rules = listOf(rule("tunnel hiccup"))
        val kept = CustomRetryRules.remove(rules, "r1")
        assertTrue(kept.isEmpty())
        assertFalse("删掉规则后同一个错误不再重试", customScope(kept).allows(vendorError))
        // 删掉规则后 CUSTOM_MATCH 这一类不再放行任何东西
        assertFalse(customScope(kept).allows(RetryErrorCategory.CUSTOM_MATCH))
    }

    @Test
    fun `editing the rule changes which errors match`() {
        val edited = CustomRetryRules.upsert(listOf(rule("tunnel hiccup")), rule("totally different"))!!
        assertEquals("同 id 覆盖而不是追加", 1, edited.size)
        val scope = customScope(edited)
        assertFalse(scope.allows(vendorError))
        assertTrue(scope.allows(IllegalStateException("definitely totally different thing")))
    }

    @Test
    fun `adding a rule appends and assigns a stable id`() {
        val first = CustomRetryRules.upsert(emptyList(), rule("alpha", id = ""))!!
        val second = CustomRetryRules.upsert(first, rule("beta", id = ""))!!
        assertEquals(2, second.size)
        assertTrue("新规则拿到可用 id", second.all { it.id.isNotEmpty() })
        // ids 稳定可复现（不依赖时间/随机）
        assertEquals(second, CustomRetryRules.upsert(first, rule("beta", id = "")))
        // 重复添加同一条不会膨胀
        assertEquals(2, CustomRetryRules.upsert(second, rule("beta", id = ""))!!.size)
    }

    @Test
    fun `disabled rule keeps existing but stops matching`() {
        val rules = listOf(rule("tunnel hiccup", enabled = false))
        assertFalse(customScope(rules).allows(vendorError))
        assertEquals("规则还在列表里", 1, rules.size)
        assertTrue("但不是可用规则", CustomRetryRules.usable(rules).isEmpty())
    }

    // ---- 写操作边界：任何规则都不能把非幂等写变成可重试 --------------------

    @Test
    fun `non idempotent writes are never replayed even when the rule matches`() {
        val scope = customScope(listOf(rule("tunnel hiccup")), safe = false)
        assertFalse(scope.allows(vendorError))
        assertFalse(scope.allows(IOException("reset")))
        assertFalse(
            "连「任何错误」模式也不越过写边界",
            RetryScope(mode = RetryScopeMode.ANY_ERROR, safeToReplay = false).allows(HttpException("boom", 503)),
        )
        assertFalse(customRuleMatches(listOf(rule("tunnel hiccup")), vendorError, safeToReplay = false))
        assertTrue(customRuleMatches(listOf(rule("tunnel hiccup")), vendorError, safeToReplay = true))
        assertFalse(isSafeToReplay(hasNonIdempotentSideEffect = true))
        assertTrue(isSafeToReplay(hasNonIdempotentSideEffect = false))
    }

    // ---- 校验：坏规则不落盘，也不假装生效 --------------------------------

    @Test
    fun `invalid rules are rejected before they are saved`() {
        assertNotNull(rule("").validate())
        assertNotNull("超长", rule("x".repeat(CustomRetryRule.MAX_PATTERN_LENGTH + 1)).validate())
        assertNotNull("空模式不能 upsert", CustomRetryRules.upsert(emptyList(), rule(" ")))
        assertNull(rule("tunnel hiccup").validate())
    }

    @Test
    fun `catastrophic regex shapes and broken regex are rejected`() {
        assertNotNull(rule("(a+)+", CustomRetryMatchMode.REGEX).validate())
        assertNotNull(rule("(a|a)*", CustomRetryMatchMode.REGEX).validate())
        assertNotNull(rule("([unclosed", CustomRetryMatchMode.REGEX).validate())
        assertNull(rule("upstream (tunnel )?hiccup", CustomRetryMatchMode.REGEX).validate())
        // 同样的文本写成 literal 是安全的：不解释元字符，所以没有回溯风险
        assertNull(rule("(a+)+", CustomRetryMatchMode.LITERAL).validate())
    }

    @Test
    fun `literal mode does not interpret regex metacharacters`() {
        val scope = customScope(listOf(rule("429 (slow)")))
        assertTrue(scope.allows(IllegalStateException("HTTP 429 (slow) please wait")))
        assertFalse("括号不是分组", scope.allows(IllegalStateException("HTTP 429 slow please wait")))
    }

    @Test
    fun `matching is case insensitive and message only`() {
        assertTrue(customScope(listOf(rule("TUNNEL HICCUP"))).allows(vendorError))
        assertFalse("没有消息的异常不匹配", customScope(listOf(rule("anything"))).allows(RuntimeException()))
    }

    // ---- 持久化：老配置是空集合，新配置原样往返 --------------------------

    @Test
    fun `old settings json without custom rules decodes to empty and unchanged behavior`() {
        val stored = """{"retryScopeMode":"CLASSIFIED","retryExtraCategories":[]}"""
        val decoded = Json.decodeFromString<RetryScope>(stored)
        assertTrue(decoded.customRules.isEmpty())
        assertTrue(decoded.safeToReplay)
        assertEquals(RetryScope.CLASSIFIED, decoded)
    }

    @Test
    fun `custom rules round trip through json`() {
        val json = Json { encodeDefaults = true }
        val original = RetryScope(
            mode = RetryScopeMode.CUSTOM,
            categories = setOf(RetryErrorCategory.CUSTOM_MATCH),
            customRules = listOf(
                rule("tunnel hiccup"),
                rule("upstream (tunnel )?hiccup", CustomRetryMatchMode.REGEX, id = "r2"),
            ),
        )
        val decoded = json.decodeFromString<RetryScope>(json.encodeToString(RetryScope.serializer(), original))
        assertEquals(original, decoded)
        assertTrue(decoded.allows(vendorError))
    }

    @Test
    fun `usable rules are deduped and capped`() {
        val dupes = listOf(rule("same"), rule("SAME", id = "r2"), rule("other", id = "r3"))
        assertEquals(2, CustomRetryRules.usable(dupes).size)

        val many = (1..CustomRetryRule.MAX_RULES + 5).map { rule("pattern-$it", id = "r$it") }
        assertEquals(CustomRetryRule.MAX_RULES, CustomRetryRules.usable(many).size)
        assertNull("超出条数上限的编辑被拒绝", CustomRetryRules.upsert(many, rule("one-more", id = "extra")))
    }
}
