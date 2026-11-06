package me.rerere.rikkahub.data.ai.inputassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 输入优化/审查的纯规则测试：不联网、不碰 Android。
 *
 * 这里锁住的都是"会花钱或会改坏请求"的判定：原文指纹、代码块保全、数字/路径不能丢、
 * 拒绝回答必须回退原话、审查输出解析不出来时不许编结论。
 */
class InputAssistRulesTest {
    // ---- 原文指纹 ----

    @Test
    fun `hash is stable and changes with the text`() {
        val a = InputAssistRules.inputHash("把 build 目录清理一下")
        assertEquals(a, InputAssistRules.inputHash("把 build 目录清理一下"))
        assertFalse(a == InputAssistRules.inputHash("把 build 目录清理一下 "))
        assertEquals(64, a.length)
    }

    // ---- 规范文本：定轮次、改写、缓存必须用同一份 ----

    @Test
    fun `canonical text joins only the real text parts`() {
        // UIMessage.toText() 会给图片这类非文本 part 也留一个分隔符（"a\n\nb"），
        // 那样定轮次和请求改写算出的指纹就永远对不上，带附件的消息会静默地失去优化
        assertEquals("a\nb", InputAssistRules.canonicalUserText(listOf("a", "b")))
        assertEquals("图片说明", InputAssistRules.canonicalUserText(listOf("图片说明")))
        assertEquals("", InputAssistRules.canonicalUserText(emptyList()))
    }

    @Test
    fun `canonical hash ignores outer whitespace but not content`() {
        val base = InputAssistRules.canonicalTextHash(listOf("第一段", "第二段"))
        // 整段首尾空白不影响：定轮次和请求改写对同一段文本必须得到同一个指纹
        assertEquals(base, InputAssistRules.canonicalTextHash(listOf("第一段\n第二段\n")))
        assertEquals(base, InputAssistRules.canonicalTextHash(listOf("  第一段\n第二段  ")))
        // 段内的空白和文字都是用户内容的一部分，不能悄悄抹掉
        assertFalse(base == InputAssistRules.canonicalTextHash(listOf("  第一段  ", "第二段")))
        assertFalse(base == InputAssistRules.canonicalTextHash(listOf("第一段", "第二段改过")))
        assertEquals(64, base.length)
    }

    // ---- 多段文本与附件：折叠会不会改变顺序 ----

    @Test
    fun `contiguous text parts may be collapsed`() {
        assertTrue(InputAssistRules.textPartsContiguous(listOf(true)))
        assertTrue(InputAssistRules.textPartsContiguous(listOf(true, true, false)))
        assertTrue(InputAssistRules.textPartsContiguous(listOf(false, true, true)))
    }

    @Test
    fun `text parts split by an attachment must not be collapsed`() {
        // [文字, 图片, 文字] 折叠后第二段会跑到图片前面 —— 那不是优化，是改用户消息的顺序
        assertFalse(InputAssistRules.textPartsContiguous(listOf(true, false, true)))
        assertFalse(InputAssistRules.textPartsContiguous(listOf(false, false)))
        assertFalse(InputAssistRules.textPartsContiguous(emptyList()))
    }

    // ---- 审查缓存键 ----

    @Test
    fun `review cache key covers assistant user and reply`() {
        val conversation = Uuid.random()
        val assistant = Uuid.random()
        val user = Uuid.random()
        val reply = Uuid.random()
        val base = InputAssistRules.reviewCacheKey(assistant, conversation, user, "用户要求", reply, "助手回答")

        assertTrue(base.contains(assistant.toString()))
        assertTrue(base.contains(conversation.toString()))
        assertTrue(base.contains(user.toString()))
        assertTrue(base.contains(reply.toString()))
        assertTrue(base.contains(InputAssistRules.inputHash("用户要求")))
        assertTrue(base.contains(InputAssistRules.inputHash("助手回答")))

        // 换助手 / 换回复 / 改一个字都必须换键：不许退回只按会话命中的旧键
        assertFalse(base == InputAssistRules.reviewCacheKey(Uuid.random(), conversation, user, "用户要求", reply, "助手回答"))
        assertFalse(base == InputAssistRules.reviewCacheKey(assistant, Uuid.random(), user, "用户要求", reply, "助手回答"))
        assertFalse(base == InputAssistRules.reviewCacheKey(assistant, conversation, user, "用户要求改", reply, "助手回答"))
        assertFalse(base == InputAssistRules.reviewCacheKey(assistant, conversation, user, "用户要求", reply, "助手回答改"))
        assertFalse(base == InputAssistRules.reviewCacheKey(assistant, conversation, Uuid.random(), "用户要求", reply, "助手回答"))
    }

    // ---- 该不该优化 ----

    @Test
    fun `short text is not optimized`() {
        assertFalse(InputAssistRules.optimizeEligible("好"))
        assertFalse(InputAssistRules.optimizeEligible("   ok  "))
    }

    @Test
    fun `prose is optimized`() {
        assertTrue(InputAssistRules.optimizeEligible("帮我把这个函数改成协程，别的别动"))
    }

    @Test
    fun `code only message is not optimized`() {
        val codeOnly = "```kotlin\nfun main() { println(1) }\n```"
        assertFalse(InputAssistRules.optimizeEligible(codeOnly))
    }

    @Test
    fun `oversized message is not optimized`() {
        val long = "a".repeat(InputAssistRules.MAX_INPUT_CHARS + 1)
        assertFalse(InputAssistRules.optimizeEligible(long))
    }

    // ---- 代码块保全 ----

    @Test
    fun `code blocks are masked and restored verbatim`() {
        val original = "把这段改成协程：\n```kotlin\nfun f() = 1\n```\n保持签名不变"
        val masked = InputAssistRules.maskCodeBlocks(original)
        assertEquals(1, masked.codeBlocks.size)
        assertEquals("```kotlin\nfun f() = 1\n```", masked.codeBlocks.single())
        assertFalse(masked.core.contains("fun f()"))
        assertTrue(masked.core.contains("⟦代码块1⟧"))

        val restored = InputAssistRules.restoreCodeBlocks(
            "把这段改成协程（保持签名不变）：\n⟦代码块1⟧",
            masked,
        )
        assertNotNull(restored)
        assertTrue(restored!!.contains("```kotlin\nfun f() = 1\n```"))
    }

    @Test
    fun `dropped or duplicated placeholder rejects the rewrite`() {
        val masked = InputAssistRules.maskCodeBlocks("解释这段：\n```\nval a = 1\n```")
        assertNull(InputAssistRules.restoreCodeBlocks("解释这段", masked))
        assertNull(InputAssistRules.restoreCodeBlocks("解释 ⟦代码块1⟧ 和 ⟦代码块1⟧", masked))
        assertNull(InputAssistRules.restoreCodeBlocks("解释 ⟦代码块2⟧", masked))
    }

    // ---- 优化结果守卫 ----

    @Test
    fun `faithful rewrite passes the guard`() {
        val original = "帮我把 report/2024 下的 3 个 csv 合一个，别改列顺序"
        val masked = InputAssistRules.maskCodeBlocks(original)
        val rewrite = "目标：把 report/2024 下的 3 个 csv 合并成一个。\n约束：不要改变列顺序。"
        assertEquals(rewrite, InputAssistRules.sanitizeOptimized(rewrite, original, masked))
    }

    @Test
    fun `rewrite that loses a number or a path is rejected`() {
        val original = "帮我把 report/2024 下的 3 个 csv 合一个"
        val masked = InputAssistRules.maskCodeBlocks(original)
        assertNull(
            InputAssistRules.sanitizeOptimized(
                "目标：把 report/2024 下的所有 csv 合并成一个。",
                original,
                masked,
            ),
        )
        assertNull(
            InputAssistRules.sanitizeOptimized(
                "目标：把 3 个 csv 合并成一个。",
                original,
                masked,
            ),
        )
    }

    @Test
    fun `refusal output falls back to the original words`() {
        val original = "帮我把 a/b 下的 2 个文件删掉，别动别的"
        val masked = InputAssistRules.maskCodeBlocks(original)
        assertNull(InputAssistRules.sanitizeOptimized("抱歉，我无法协助这类请求。", original, masked))
    }

    @Test
    fun `quoted wording must survive`() {
        val original = "把按钮文案改成\"立即保存\"，别改别的"
        val masked = InputAssistRules.maskCodeBlocks(original)
        assertNull(InputAssistRules.sanitizeOptimized("目标：把按钮文案改一下。", original, masked))
        assertNotNull(
            InputAssistRules.sanitizeOptimized(
                "目标：把按钮文案改成\"立即保存\"；约束：不要改其它文案。",
                original,
                masked,
            ),
        )
    }

    @Test
    fun `whole answer wrapped in a fence is unwrapped`() {
        val original = "帮我把 scripts/run.sh 里的 2 行注释去掉"
        val masked = InputAssistRules.maskCodeBlocks(original)
        val wrapped = "```\n目标：去掉 scripts/run.sh 里的 2 行注释。\n```"
        assertEquals(
            "目标：去掉 scripts/run.sh 里的 2 行注释。",
            InputAssistRules.sanitizeOptimized(wrapped, original, masked),
        )
    }

    @Test
    fun `empty output is rejected`() {
        val original = "帮我看看这个思路对不对，先别改代码"
        assertNull(InputAssistRules.sanitizeOptimized("   ", original, InputAssistRules.maskCodeBlocks(original)))
    }

    // ---- 自检提示 ----

    @Test
    fun `self check tail says it is not independent verification`() {
        val tail = InputAssistRules.selfCheckTail()
        assertTrue(tail.contains("不是独立验证"))
        assertTrue(tail.contains("同一模型"))
    }

    // ---- 审查解析 ----

    @Test
    fun `standard review output is parsed`() {
        val raw = """
            VERDICT: CONCERN
            FINDINGS:
            - 没有说明是否真的跑过测试
            - 建议的路径和用户给的路径不一致
            SUGGESTION: 补一句"未运行测试"
            LIMITS: 只看文本，未执行任何命令
        """.trimIndent()
        val parsed = InputAssistRules.parseReview(raw)
        assertNotNull(parsed)
        assertEquals(InputReviewVerdict.CONCERN, parsed!!.verdict)
        assertEquals(2, parsed.findings.size)
        assertEquals("补一句\"未运行测试\"", parsed.suggestion)
        assertTrue(parsed.limits.contains("未执行"))
    }

    @Test
    fun `chinese labels are parsed too`() {
        val raw = """
            VERDICT: 风险
            FINDINGS:
            - 回答声称编译通过，但没有任何构建证据
            SUGGESTION: 明确标注未验证
            LIMITS: 只读文本
        """.trimIndent()
        val parsed = InputAssistRules.parseReview(raw)
        assertNotNull(parsed)
        assertEquals(InputReviewVerdict.RISK, parsed!!.verdict)
        assertEquals(1, parsed.findings.size)
    }

    @Test
    fun `empty findings marker is filtered out`() {
        val raw = """
            VERDICT: OK
            FINDINGS:
            - 无
            SUGGESTION: 无
            LIMITS: 只读文本
        """.trimIndent()
        val parsed = InputAssistRules.parseReview(raw)
        assertNotNull(parsed)
        assertEquals(InputReviewVerdict.OK, parsed!!.verdict)
        assertTrue(parsed.findings.isEmpty())
        assertNull(parsed.suggestion)
    }

    @Test
    fun `garbage review output yields nothing instead of an invented verdict`() {
        assertNull(InputAssistRules.parseReview(""))
        assertNull(InputAssistRules.parseReview("我不确定，看起来还行。"))
    }

    @Test
    fun `review prompt forbids tools and replay`() {
        val prompt = InputAssistRules.reviewPrompt("用户要求", "助手回答")
        assertTrue(prompt.contains("不执行任何工具"))
        assertTrue(prompt.contains("不重放任务"))
        assertTrue(prompt.contains("VERDICT"))
    }

    @Test
    fun `optimization prompt forbids adding permissions or goals`() {
        val prompt = InputAssistRules.optimizationSystemPrompt()
        assertTrue(prompt.contains("不得替用户新增授权"))
        assertTrue(prompt.contains("⟦代码块N⟧"))
    }
}
