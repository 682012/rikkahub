package me.rerere.rikkahub.data.ai.inputassist

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 请求改写：只有那条被优化的用户消息变，附件原样留着，别的历史一个字不动。
 *
 * 这是"优化版发给模型、原话留在会话里"这条线的最后一环，改错就会变成篡改用户消息。
 */
class InputAssistRequestRewriteTest {
    /** toText() 会把非文本 part 也拼进去（补空串），这里只看真正的文本 */
    private fun UIMessage.textParts(): String =
        parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    private fun state(
        conversationId: Uuid,
        messageId: Uuid,
        text: String? = "优化后的要求",
        selfCheck: Boolean = false,
        /**
         * 定这一轮时那条原话（规范文本 = 只含 Text part、单换行连接）。
         * null 表示状态里没有指纹：一律不许替换。
         */
        stagedRawText: String? = null,
    ) = InputAssistTurnState(
        conversationId = conversationId,
        messageId = messageId,
        assistantId = Uuid.random(),
        optimization = if (text == null) {
            InputOptimizationOutcome(InputOptimizationStatus.NOT_REQUESTED)
        } else {
            InputOptimizationOutcome(InputOptimizationStatus.OPTIMIZED, text, "fast-model")
        },
        selfCheck = selfCheck,
        originalHash = stagedRawText?.let { InputAssistRules.canonicalTextHash(listOf(it)) },
    )

    @Test
    fun `no staged state leaves the request untouched`() {
        val messages = listOf(UIMessage.user("原话"))
        assertEquals(messages, rewriteRequestMessages(messages, null))
    }

    @Test
    fun `only the staged user message is rewritten`() {
        val conversation = Uuid.random()
        val target = Uuid.random()
        val earlier = UIMessage(id = Uuid.random(), role = MessageRole.USER, parts = listOf(UIMessagePart.Text("更早的原话")))
        val userMessage = UIMessage(id = target, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("原话")))
        val assistantMessage = UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("上一轮回答")))
        val messages = listOf(earlier, assistantMessage, userMessage)

        val rewritten = rewriteRequestMessages(
            messages,
            state(conversation, target, text = "优化版：目标/约束都写清楚", stagedRawText = "原话"),
        )

        assertEquals(messages, listOf(earlier, assistantMessage, userMessage))
        assertEquals("更早的原话", rewritten[0].toText())
        assertEquals("上一轮回答", rewritten[1].toText())
        assertEquals("优化版：目标/约束都写清楚", rewritten[2].toText())
        // 原对象没被改
        assertEquals("原话", userMessage.toText())
    }

    @Test
    fun `attachments stay in place while the text is replaced`() {
        val conversation = Uuid.random()
        val target = Uuid.random()
        val image = UIMessagePart.Image("file:///tmp/a.png")
        val userMessage = UIMessage(
            id = target,
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("看看这张图里的报错"), image),
        )

        val rewritten = rewriteRequestMessages(
            listOf(userMessage),
            state(conversation, target, stagedRawText = "看看这张图里的报错"),
        ).single()

        assertEquals("优化后的要求", rewritten.textParts())
        assertEquals(listOf(image), rewritten.parts.filterIsInstance<UIMessagePart.Image>())
        assertEquals(2, rewritten.parts.size)
    }

    @Test
    fun `multiple contiguous text parts collapse into the optimized one`() {
        val conversation = Uuid.random()
        val target = Uuid.random()
        val image = UIMessagePart.Image("file:///tmp/a.png")
        val userMessage = UIMessage(
            id = target,
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("第一段"), UIMessagePart.Text("第二段"), image),
        )

        val rewritten = rewriteRequestMessages(
            listOf(userMessage),
            state(conversation, target, stagedRawText = "第一段\n第二段"),
        ).single()

        assertEquals("优化后的要求", rewritten.textParts())
        assertTrue(rewritten.parts.contains(image))
        assertEquals(2, rewritten.parts.size)
    }

    @Test
    fun `text split by an attachment is never collapsed into one part`() {
        val conversation = Uuid.random()
        val target = Uuid.random()
        val image = UIMessagePart.Image("file:///tmp/a.png")
        val userMessage = UIMessage(
            id = target,
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("第一段"), image, UIMessagePart.Text("第二段")),
        )

        val rewritten = rewriteRequestMessages(
            listOf(userMessage),
            state(conversation, target, stagedRawText = "第一段\n第二段"),
        ).single()

        // 折叠会把"第二段"挪到图片前面：顺序就是用户消息的一部分，宁可用原话
        assertEquals("第一段\n第二段", rewritten.textParts())
        assertEquals(userMessage.parts, rewritten.parts)
    }

    @Test
    fun `self check tail is appended without touching other messages`() {
        val conversation = Uuid.random()
        val target = Uuid.random()
        val earlier = UIMessage(id = Uuid.random(), role = MessageRole.USER, parts = listOf(UIMessagePart.Text("更早")))
        val userMessage = UIMessage(id = target, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("帮我改配置")))

        val rewritten = rewriteRequestMessages(
            listOf(earlier, userMessage),
            state(conversation, target, text = null, selfCheck = true),
        )

        assertEquals("更早", rewritten[0].toText())
        assertTrue(rewritten[1].toText().startsWith("帮我改配置"))
        assertTrue(rewritten[1].toText().contains("不是独立验证"))
        assertEquals("帮我改配置", userMessage.toText())
    }

    @Test
    fun `an edited message is never rewritten with the stale optimized text`() {
        val conversation = Uuid.random()
        val target = Uuid.random()
        val userMessage = UIMessage(id = target, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("后来的原话")))
        val stale = InputAssistTurnState(
            conversationId = conversation,
            messageId = target,
            assistantId = Uuid.random(),
            optimization = InputOptimizationOutcome(InputOptimizationStatus.OPTIMIZED, "旧优化版", "fast-model"),
            originalHash = InputAssistRules.canonicalTextHash(listOf("原来的原话")),
        )

        val rewritten = rewriteRequestMessages(listOf(userMessage), stale).single()
        assertEquals("后来的原话", rewritten.textParts())
    }

    @Test
    fun `a state without a fingerprint never replaces the text`() {
        val conversation = Uuid.random()
        val target = Uuid.random()
        val userMessage = UIMessage(id = target, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("原话")))
        val noHash = InputAssistTurnState(
            conversationId = conversation,
            messageId = target,
            assistantId = Uuid.random(),
            optimization = InputOptimizationOutcome(InputOptimizationStatus.OPTIMIZED, "优化版", "fast-model"),
        )

        // 没有指纹就没有"这段文字还是那段"的证据：宁可发原话
        assertEquals("原话", rewriteRequestMessages(listOf(userMessage), noHash).single().textParts())
    }

    @Test
    fun `a matching fingerprint still rewrites`() {
        val conversation = Uuid.random()
        val target = Uuid.random()
        val userMessage = UIMessage(id = target, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("原话")))
        val staged = InputAssistTurnState(
            conversationId = conversation,
            messageId = target,
            assistantId = Uuid.random(),
            optimization = InputOptimizationOutcome(InputOptimizationStatus.OPTIMIZED, "优化版", "fast-model"),
            originalHash = InputAssistRules.inputHash("原话"),
        )

        val rewritten = rewriteRequestMessages(listOf(userMessage), staged).single()
        assertEquals("优化版", rewritten.textParts())
    }

    @Test
    fun `a cached rewrite is used only when the text is still the same`() {
        val conversation = Uuid.random()
        val target = Uuid.random()
        val userMessage = UIMessage(id = target, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("原话")))
        val cached = InputAssistTurnState(
            conversationId = conversation,
            messageId = target,
            assistantId = Uuid.random(),
            optimization = InputOptimizationOutcome(
                InputOptimizationStatus.CACHED, "缓存优化版", "fast-model", cached = true,
            ),
            originalHash = InputAssistRules.inputHash("原话"),
        )

        assertEquals("缓存优化版", rewriteRequestMessages(listOf(userMessage), cached).single().textParts())
    }

    @Test
    fun `failed optimization leaves the user words in the request`() {
        val conversation = Uuid.random()
        val target = Uuid.random()
        val userMessage = UIMessage(id = target, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("原话")))
        val failed = InputAssistTurnState(
            conversationId = conversation,
            messageId = target,
            assistantId = Uuid.random(),
            optimization = InputOptimizationOutcome(InputOptimizationStatus.FAILED, "半截结果"),
        )

        val rewritten = rewriteRequestMessages(listOf(userMessage), failed).single()
        assertEquals("原话", rewritten.toText())
        assertFalse(rewritten.toText().contains("半截结果"))
    }

    @Test
    fun `cancelled optimization leaves the user words in the request`() {
        val conversation = Uuid.random()
        val target = Uuid.random()
        val userMessage = UIMessage(id = target, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("原话")))
        val cancelled = InputAssistTurnState(
            conversationId = conversation,
            messageId = target,
            assistantId = Uuid.random(),
            optimization = InputOptimizationOutcome(
                InputOptimizationStatus.FAILED,
                failureReason = "optimization cancelled",
            ),
            originalHash = InputAssistRules.inputHash("原话"),
        )

        assertEquals("原话", rewriteRequestMessages(listOf(userMessage), cancelled).single().textParts())
    }
}
