package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具参数在流式过程中/断流之后必须保留，并且只有"正常结束 + 参数完整"才允许执行。
 *
 * 这些用例都直接打 [StreamChunkHandler]：它是唯一把 Provider 增量拼成 [UIMessagePart.Tool] 的地方。
 */
class ToolInputStateStreamingTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun assistant() = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())

    private fun StreamChunkHandler.start(id: String, name: String = "write") =
        handle(listOf(assistant()), StreamChunk.ToolCallStart(id, name))

    private fun List<UIMessage>.tool(): UIMessagePart.Tool =
        last().parts.filterIsInstance<UIMessagePart.Tool>().single()

    @Test
    fun `partial input is kept and never runnable when the stream breaks`() {
        val handler = StreamChunkHandler()
        var messages = handler.start("call-1")
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call-1", inputDelta = HALF_JSON))
        // 断线/取消：既没有 ToolCallEnd 也没有 Finish，只能靠 interrupted 收尾
        messages = handler.interrupted(messages)

        val part = messages.tool()
        // 已经收到的半截参数一个字都不能少，这一条要能原样落盘给用户看
        assertEquals(HALF_JSON, part.input)
        assertEquals(ToolInputState.INTERRUPTED, part.inputState)
        assertFalse(part.isInputComplete)
        assertFalse(part.isInputRunnable)
        assertFalse(part.isPending)
        assertNull(part.parseInputOrNull())
        // 即使审批状态是"已批准"，参数没收到完整的调用也不能恢复执行
        assertFalse(part.copy(approvalState = ToolApprovalState.Approved).canResumeExecution)
    }

    @Test
    fun `tool call end makes the input complete and runnable`() {
        val handler = StreamChunkHandler()
        var messages = handler.start("call-1")
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call-1", inputDelta = FULL_JSON))
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call-1"))

        val part = messages.tool()
        assertEquals(FULL_JSON, part.input)
        assertEquals(ToolInputState.COMPLETE, part.inputState)
        assertTrue(part.isInputRunnable)
        assertEquals(FULL_JSON, part.inputAsJson().toString())
    }

    @Test
    fun `a tool without arguments is complete and runnable`() {
        val handler = StreamChunkHandler()
        var messages = handler.start("call-1", "get_time")
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call-1"))

        val part = messages.tool()
        assertEquals("", part.input)
        assertEquals(ToolInputState.COMPLETE, part.inputState)
        // 合法无参调用：空入参等于 {}，不能因为"没有参数"就被当成不完整
        assertTrue(part.isInputRunnable)
    }

    @Test
    fun `max tokens truncation keeps the partial input and blocks execution`() {
        val handler = StreamChunkHandler()
        var messages = handler.start("call-1")
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call-1", inputDelta = HALF_JSON))
        messages = handler.handle(messages, StreamChunk.Finish(finishReason = "max_tokens"))

        val part = messages.tool()
        assertEquals(HALF_JSON, part.input)
        assertEquals(ToolInputState.INTERRUPTED, part.inputState)
        assertFalse(part.isInputRunnable)
    }

    @Test
    fun `truncated arguments that happen to parse are still interrupted`() {
        val handler = StreamChunkHandler()
        var messages = handler.start("call-1")
        // 正好断在一个已闭合的子对象后面：这段文本本身是合法 JSON，按"完整参数"执行会用默认值
        // 悄悄改掉用户的东西，所以截断的 finish reason 必须压过"能解析"
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call-1", inputDelta = TRUNCATED_BUT_PARSABLE))
        messages = handler.handle(messages, StreamChunk.Finish(finishReason = "length"))

        val part = messages.tool()
        assertEquals(TRUNCATED_BUT_PARSABLE, part.input)
        assertTrue(part.parseInputOrNull() != null)
        assertEquals(ToolInputState.INTERRUPTED, part.inputState)
        assertFalse(part.isInputRunnable)
    }

    @Test
    fun `a provider that never sends tool call end still completes valid arguments`() {
        val handler = StreamChunkHandler()
        var messages = handler.start("call-1")
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call-1", inputDelta = FULL_JSON))
        // 有些 OpenAI 兼容中转只发 finish，不发 tool call 的结束事件
        messages = handler.handle(messages, StreamChunk.Finish(finishReason = "tool_calls"))

        val part = messages.tool()
        assertEquals(ToolInputState.COMPLETE, part.inputState)
        assertTrue(part.isInputRunnable)
    }

    @Test
    fun `finish does not complete an incomplete call from an earlier step`() {
        val previous = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-old",
                    toolName = "write",
                    input = HALF_JSON,
                    inputState = ToolInputState.STREAMING,
                )
            ),
        )
        val handler = StreamChunkHandler()
        var messages = listOf(previous)
        messages = handler.handle(messages, StreamChunk.TextStart("text"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text", "hi"))
        messages = handler.handle(messages, StreamChunk.Finish(finishReason = "stop"))

        val part = messages.tool()
        // 上一次中断留下的半截参数不能被这一次的 Finish "顺手"改成完整状态
        assertEquals(HALF_JSON, part.input)
        assertEquals(ToolInputState.STREAMING, part.inputState)
        assertFalse(part.isInputRunnable)
    }

    @Test
    fun `interrupted only touches calls created by this handler`() {
        val previous = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-old",
                    toolName = "write",
                    input = HALF_JSON,
                    inputState = ToolInputState.STREAMING,
                )
            ),
        )
        val handler = StreamChunkHandler()
        var messages = listOf(previous)
        messages = handler.handle(messages, StreamChunk.ToolCallStart("call-new", "read"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call-new", inputDelta = HALF_JSON))
        messages = handler.interrupted(messages)

        val tools = messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(ToolInputState.STREAMING, tools.single { it.toolCallId == "call-old" }.inputState)
        assertEquals(ToolInputState.INTERRUPTED, tools.single { it.toolCallId == "call-new" }.inputState)
    }

    @Test
    fun `input state survives serialization and legacy records stay complete`() {
        val interrupted = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "write",
            input = HALF_JSON,
            inputState = ToolInputState.INTERRUPTED,
        )
        val encoded = json.encodeToString(UIMessagePart.serializer(), interrupted)
        assertTrue(encoded, encoded.contains("inputState"))

        val decoded = json.decodeFromString(UIMessagePart.serializer(), encoded) as UIMessagePart.Tool
        assertEquals(ToolInputState.INTERRUPTED, decoded.inputState)
        assertEquals(HALF_JSON, decoded.input)

        // 旧记录没有这个字段：必须按 COMPLETE 解码，老消息和非流式响应行为不变
        val legacyEncoded = encoded.replace(Regex(",\\s*\"inputState\"\\s*:\\s*\"[a-z]+\""), "")
        assertFalse(legacyEncoded, legacyEncoded.contains("inputState"))
        val legacy = json.decodeFromString(UIMessagePart.serializer(), legacyEncoded) as UIMessagePart.Tool
        assertEquals(ToolInputState.COMPLETE, legacy.inputState)
        assertEquals(HALF_JSON, legacy.input)
        // 旧记录里参数就是半截 JSON 时也不能当成"没有参数"去执行
        assertFalse(legacy.isInputRunnable)
    }

    private companion object {
        /** 参数还没发完就被切断。 */
        const val HALF_JSON = """{"path":"/da"""

        const val FULL_JSON = """{"path":"/data"}"""

        /** 恰好断在已闭合的子对象后面，本身能解析成 JSON。 */
        const val TRUNCATED_BUT_PARSABLE = """{"path":"/data","opts":{}}"""
    }
}
