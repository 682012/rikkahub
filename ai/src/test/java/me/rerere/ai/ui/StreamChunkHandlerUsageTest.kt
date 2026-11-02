package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Claude `pause_turn` 会把一次逻辑 assistant 回合拆成多次 HTTP 请求，每次请求都按完整上下文重新
 * 计费，并各自返回一份 usage。因此 [handleTextGenerationResult] 在"末尾已经是 assistant"时合并的是
 * **两次不同请求**：用量必须累加，不能用最新值覆盖。
 *
 * 同时锁定 [UIMessage.lastUsage] 的契约：[UIMessage.usage] 是全轮累计值，`lastUsage` 只能是本次
 * 请求自己的用量（显式的 0 也是真实值），缺失 usage 时不能把累计值冒充成"最后一次请求"。
 */
class StreamChunkHandlerUsageTest {
    private val model = Model(modelId = "test-model")

    private fun result(
        text: String,
        usage: TokenUsage?,
        finishReason: String? = "end_turn",
    ) = TextGenerationResult(
        id = "resp-$text",
        model = model.modelId,
        message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text))),
        finishReason = finishReason,
        usage = usage,
    )

    @Test
    fun `pause turn continuations must accumulate usage instead of overwriting`() {
        var messages = listOf(UIMessage.user("search"))
        // 第一次请求命中缓存
        messages = messages.handleTextGenerationResult(
            result("step 1", TokenUsage(promptTokens = 5000, completionTokens = 10, cachedTokens = 5000, totalTokens = 5010)),
            model,
        )
        // 续写请求没有命中缓存，prompt 是完整上下文
        messages = messages.handleTextGenerationResult(
            result("step 2", TokenUsage(promptTokens = 6000, completionTokens = 20, cachedTokens = 0, totalTokens = 6020)),
            model,
        )

        val assistant = messages.last()
        assertEquals(2, messages.size)
        assertEquals(MessageRole.ASSISTANT, assistant.role)
        // prompt / cached / total 都按请求累加：cached 不能被最后一次的 0 盖掉
        assertEquals(
            TokenUsage(promptTokens = 11000, completionTokens = 30, cachedTokens = 5000, totalTokens = 11030),
            assistant.usage,
        )
        // 最后一次请求自己的用量才是上下文占用
        assertEquals(
            TokenUsage(promptTokens = 6000, completionTokens = 20, cachedTokens = 0, totalTokens = 6020),
            assistant.lastUsage,
        )
        assertEquals(6020, assistant.contextTokens())
        assertNotEquals(assistant.usage, assistant.lastRequestUsage())
    }

    @Test
    fun `last usage keeps a genuine zero from the final request`() {
        var messages = listOf(UIMessage.user("hello"))
        messages = messages.handleTextGenerationResult(
            result("step 1", TokenUsage(promptTokens = 100, completionTokens = 10, cachedTokens = 0, totalTokens = 110)),
            model,
        )
        // 最后一次请求显式上报全 0：这是真实值，不能被上一条请求的数字顶替
        messages = messages.handleTextGenerationResult(result("step 2", TokenUsage()), model)

        val assistant = messages.last()
        assertEquals(
            TokenUsage(promptTokens = 100, completionTokens = 10, cachedTokens = 0, totalTokens = 110),
            assistant.usage,
        )
        assertEquals(TokenUsage(), assistant.lastUsage)
        assertEquals(TokenUsage(), assistant.lastRequestUsage())
        assertEquals(0, assistant.contextTokens())
    }

    @Test
    fun `missing usage must not pass the accumulated total as the last request`() {
        var messages = listOf(UIMessage.user("hello"))
        messages = messages.handleTextGenerationResult(
            result("step 1", TokenUsage(promptTokens = 100, completionTokens = 10, cachedTokens = 0, totalTokens = 110)),
            model,
        )
        messages = messages.handleTextGenerationResult(
            result("step 2", TokenUsage(promptTokens = 200, completionTokens = 20, cachedTokens = 0, totalTokens = 220)),
            model,
        )
        assertEquals(
            TokenUsage(promptTokens = 300, completionTokens = 30, cachedTokens = 0, totalTokens = 330),
            messages.last().usage,
        )

        // 续写请求没带 usage：累计值保持，但 lastUsage 不能变成 330
        messages = messages.handleTextGenerationResult(result("step 3", usage = null), model)

        val assistant = messages.last()
        assertEquals(
            TokenUsage(promptTokens = 300, completionTokens = 30, cachedTokens = 0, totalTokens = 330),
            assistant.usage,
        )
        assertEquals(
            TokenUsage(promptTokens = 200, completionTokens = 20, cachedTokens = 0, totalTokens = 220),
            assistant.lastUsage,
        )
    }

    @Test
    fun `appending an assistant answer must not count the user message usage`() {
        var messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("hi")),
                usage = TokenUsage(promptTokens = 777, completionTokens = 0, cachedTokens = 0, totalTokens = 777),
            ),
        )

        messages = messages.handleTextGenerationResult(
            result("answer", TokenUsage(promptTokens = 50, completionTokens = 5, cachedTokens = 0, totalTokens = 55)),
            model,
        )

        assertEquals(2, messages.size)
        assertEquals(
            TokenUsage(promptTokens = 777, completionTokens = 0, cachedTokens = 0, totalTokens = 777),
            messages.first().usage,
        )
        val assistant = messages.last()
        assertEquals(
            TokenUsage(promptTokens = 50, completionTokens = 5, cachedTokens = 0, totalTokens = 55),
            assistant.usage,
        )
        assertEquals(55, assistant.contextTokens())
        assertNull(messages.first().lastUsage)
    }
}
