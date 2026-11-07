package me.rerere.ai.agent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * m3 会话鞭策纯策略的最小回归：不联网、不建 AgentLoop，只验证"什么时候推、什么时候不推"。
 *
 * 重点在 false positive：闲聊、短答、已动手、已达上限、等审批都必须一次都不推。
 */
class ConversationNudgePolicyTest {
    private val buildRequest = "帮我修一下这个构建脚本，让它能编译通过。"

    private fun decide(
        userRequest: String = buildRequest,
        replyText: String = "我会先看构建脚本，然后改掉错误的依赖坐标，最后跑一次编译验证。",
        toolCalls: Int = 0,
        executed: Int = 0,
        markers: List<String> = emptyList(),
        maxAttempts: Int = ConversationNudge.DEFAULT_MAX_ATTEMPTS,
        tone: NudgeTone = NudgeTone.ENCOURAGING,
        custom: String? = null,
    ) = ConversationNudge.decide(
        NudgeRequest(
            userRequest = userRequest,
            replyText = replyText,
            toolCalls = toolCalls,
            executedToolCalls = executed,
            noticeMarkers = markers,
            maxAttempts = maxAttempts,
            tone = tone,
            customInstruction = custom,
        )
    )

    // ---------- OFF：预算为 0 时第一条守卫就先停 ----------

    @Test
    fun `off means no nudge even for a plan only reply`() {
        // 开关关闭时上层根本不会调用 decide；这里验证次数为 0 也不会凭空产出内容
        val decision = decide(markers = emptyList(), maxAttempts = 0)
        assertNull(decision.prompt)
        assertEquals("attempts-exhausted", decision.reason)
    }

    // ---------- 闲聊 / 短问答：不推 ----------

    @Test
    fun `chitchat is never nudged`() {
        val decision = decide(
            userRequest = "你好，今天怎么样",
            replyText = "我很好，谢谢！有什么可以帮你的随时说，我接下来会一直在这里。",
        )
        assertNull(decision.prompt)
        assertTrue(decision.reason.startsWith("not-an-action-request"))
    }

    @Test
    fun `question about how something works is not an action request`() {
        val decision = decide(
            userRequest = "这个为什么编译不过？",
            replyText = "一般是依赖坐标写错了。你可以看看 build.gradle.kts，我接下来会详细解释原因。",
        )
        assertNull(decision.prompt)
    }

    @Test
    fun `creative writing request is not an action request`() {
        val decision = decide(
            userRequest = "帮我写一篇关于秋天的散文",
            replyText = "好的，我会围绕落叶和黄昏写三段，接下来开始动笔，然后润色一遍。",
        )
        assertNull(decision.prompt)
    }

    @Test
    fun `short answer is not nudged`() {
        val decision = decide(replyText = "好的。")
        assertNull(decision.prompt)
        assertTrue(decision.reason.contains("too-short"))
    }

    // ---------- 计划无执行：推一次 ----------

    @Test
    fun `plan only with no tool call is nudged once`() {
        val decision = decide()
        assertNotNull(decision.prompt)
        assertEquals("attempt-1", decision.prompt!!.marker)
        assertEquals("推进提醒", decision.prompt!!.noticeTitle)
        // 提示语必须鼓励且具体：目标与授权不变、别重复方案、挑最短一步真做
        val text = decision.prompt!!.noticeText
        assertTrue(text.contains("unchanged"))
        assertTrue(text.contains("Do not restate the plan"))
        assertFalse("must not shame the model", text.contains("lazy") || text.contains("excuse"))
    }

    @Test
    fun `neutral tone says nothing emotional`() {
        val decision = decide(tone = NudgeTone.NEUTRAL)
        val text = decision.prompt!!.noticeText
        assertTrue(text.startsWith("Nudge (not from the user). Current state"))
    }

    @Test
    fun `custom instruction is appended verbatim`() {
        val decision = decide(custom = "先跑最窄的检查")
        assertTrue(decision.prompt!!.noticeText.contains("先跑最窄的检查"))
    }

    // ---------- 已有执行：不推 ----------

    @Test
    fun `a reply that already called a tool is not nudged`() {
        val decision = decide(toolCalls = 2, executed = 2)
        assertNull(decision.prompt)
        assertEquals("tool-progress", decision.reason)
    }

    @Test
    fun `completion evidence without future tense is not nudged`() {
        val decision = decide(replyText = "我已经修好依赖坐标，并跑过一次编译，结果是 BUILD SUCCESSFUL。")
        // 有完成证据：不会被当成"只说了计划"
        assertNull(decision.prompt)
    }

    // ---------- 已提醒达限：不推 ----------

    @Test
    fun `once the budget is used the same reply is not nudged again`() {
        val first = decide()
        assertNotNull(first.prompt)
        val second = decide(markers = listOf("attempt-1"))
        assertNull(second.prompt)
        assertEquals("attempts-exhausted", second.reason)
    }

    @Test
    fun `max attempts is clamped to one through three`() {
        assertEquals(1, ConversationNudge.clampAttempts(0))
        assertEquals(1, ConversationNudge.clampAttempts(-5))
        assertEquals(2, ConversationNudge.clampAttempts(2))
        assertEquals(3, ConversationNudge.clampAttempts(9))
        // 3 次预算：前两次还能推，用满之后停
        assertNotNull(decide(maxAttempts = 3).prompt)
        assertNotNull(decide(maxAttempts = 3, markers = listOf("attempt-1")).prompt)
        assertNull(decide(maxAttempts = 3, markers = listOf("attempt-1", "attempt-2", "attempt-3")).prompt)
    }

    // ---------- 等审批：不推 ----------

    @Test
    fun `reply that is waiting for approval is not nudged`() {
        val decision = decide(
            replyText = "这个命令需要你确认才能执行，我需要你的授权才能继续，确认后我再跑。",
        )
        assertNull(decision.prompt)
        assertTrue(decision.reason.contains("reasoned-stop"))
    }

    @Test
    fun `refusal or missing permission is not nudged`() {
        val decision = decide(
            replyText = "我没有权限修改这个文件，需要你先授权目录访问，我才能继续执行。",
        )
        assertNull(decision.prompt)
    }

    // ---------- 计数落在消息上，恢复后不重置 ----------

    @Test
    fun `markers are read from the notice metadata of the message`() {
        val nudge = NudgePrompt("推进提醒", "do the next step", "attempt-1").toNoticePart()
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("plan"), nudge),
        )
        assertEquals(1, ConversationNudge.usedAttempts(message))
        assertEquals(listOf("attempt-1"), ConversationNudge.markersOf(message))
        // 普通 Notice（后台事件等）不算推送次数
        val withBackground = message.copy(
            parts = message.parts + UIMessagePart.Notice(title = "后台", text = "命令结束")
        )
        assertEquals(1, ConversationNudge.usedAttempts(withBackground))
        // 恢复（序列化/反序列化）后 marker 仍在 metadata 里，计数不会重置
        assertEquals("attempt-1", ConversationNudge.markersOf(withBackground).single())
    }
}
