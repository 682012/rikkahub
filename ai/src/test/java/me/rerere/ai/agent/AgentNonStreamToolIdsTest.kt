package me.rerere.ai.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 非流式步骤追加 part 时对 toolCallId 的处理。
 *
 * 不同模型之间 id 的格式互不相干，同一个 id 会在后续 step 或同一次响应里重复出现；工具结果按 id 写回，
 * 所以重复的 id 必须在本步就换掉，否则后执行的调用会覆盖之前步骤已经执行完的结果。
 */
class AgentNonStreamToolIdsTest {
    private val model = Model(modelId = "test-model")
    private val provider = NonStreamIdsProvider()
    private val hooks = NonStreamIdsHooks(model)

    private fun request(
        messages: List<UIMessage> = listOf(UIMessage.user("hi")),
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
    ) = AgentRequest(
        provider = provider,
        providerSetting = ProviderSetting.OpenAI(),
        messages = messages,
        tools = tools,
        stream = false,
        maxSteps = maxSteps,
        retry = RetryPolicy.NONE,
    )

    private suspend fun runToEnd(request: AgentRequest = request()) =
        runAgent(request, hooks).last() as AgentEvent.Finished

    @Test
    fun `only conflicting ids are renamed and everything else is kept`() {
        val existing = listOf(UIMessagePart.Tool("call-1", "echo", """{"q":"x"}"""))
        val text = UIMessagePart.Text(text = "hi", metadata = buildJsonObject { put("k", "v") })

        // 正常 id：整份新 part 原样返回，非工具 part 仍是同一个实例
        val untouchedCall = UIMessagePart.Tool("call-2", "echo", """{"q":"y"}""", metadata = buildJsonObject { put("m", "1") })
        val untouched = listOf<UIMessagePart>(text, untouchedCall)
        val same = normalizeNewToolCallIds(existing, untouched)
        assertSame(untouched, same)
        assertSame(text, same[0])
        assertSame(untouchedCall, same[1])

        // 撞 id：只换 id，输入、结果、metadata 和其它字段都不动
        val collided = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "echo",
            input = """{"q":"z"}""",
            output = listOf(UIMessagePart.Text("kept")),
            approvalState = ToolApprovalState.Approved,
            metadata = buildJsonObject { put("m", "2") },
        )
        val normalized = normalizeNewToolCallIds(existing, listOf(collided)).single() as UIMessagePart.Tool

        assertEquals("call-1-1", normalized.toolCallId)
        assertEquals(collided.copy(toolCallId = normalized.toolCallId), normalized)
        assertEquals(collided.metadata, normalized.metadata)
        assertEquals(listOf(UIMessagePart.Text("kept")), normalized.output)
    }

    @Test
    fun `a later step reusing the same tool call id keeps both results`() = runTest {
        provider.result(assistant(UIMessagePart.Tool("call-1", "echo", """{"q":"first"}""")))
        provider.result(assistant(UIMessagePart.Tool("call-1", "echo", """{"q":"second"}""")))
        provider.result(assistant(UIMessagePart.Text("done")))

        val message = runToEnd(request(tools = listOf(echo))).message

        val calls = message.getTools()
        assertEquals(2, calls.size)
        // 每个调用拿回自己的结果，之前的执行结果没有被覆盖
        assertEquals(listOf("first", "second"), calls.map { it.query() })
        assertEquals(listOf("first", "second"), calls.map { it.outputText() })
        assertEquals(2, calls.map { it.toolCallId }.toSet().size)
        // 第一步的 id 本来就是唯一的，保持原样
        assertEquals("call-1", calls.first().toolCallId)
        assertTrue(calls.all { it.isExecuted })
    }

    @Test
    fun `calls with empty ids in one response stay independent`() = runTest {
        provider.result(
            assistant(
                UIMessagePart.Tool("", "echo", """{"q":"a"}"""),
                UIMessagePart.Tool("", "echo", """{"q":"b"}"""),
            )
        )
        provider.result(assistant(UIMessagePart.Text("done")))

        val calls = runToEnd(request(tools = listOf(echo))).message.getTools()

        assertEquals(2, calls.size)
        assertTrue(calls.all { it.toolCallId.isNotBlank() })
        assertEquals(2, calls.map { it.toolCallId }.toSet().size)
        assertEquals(listOf("a", "b"), calls.map { it.outputText() })
    }

    @Test
    fun `normal ids and metadata are left untouched`() = runTest {
        val toolMetadata = buildJsonObject {
            put("provider", "fake")
            put("index", 1)
        }
        val textMetadata = buildJsonObject { put("kind", "preamble") }
        provider.result(
            assistant(
                UIMessagePart.Text(text = "looking", metadata = textMetadata),
                UIMessagePart.Tool("call-9", "echo", """{"q":"x"}""", metadata = toolMetadata),
            )
        )
        provider.result(assistant(UIMessagePart.Text("done")))

        val message = runToEnd(request(tools = listOf(echo))).message

        assertEquals(3, message.parts.size)
        val text = message.parts.first() as UIMessagePart.Text
        assertEquals("looking", text.text)
        assertEquals(textMetadata, text.metadata)
        val call = message.getTools().single()
        assertEquals("call-9", call.toolCallId)
        assertEquals(toolMetadata, call.metadata)
        assertEquals("x", call.outputText())
        assertEquals(listOf("looking", "done"), message.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
    }

    @Test
    fun `calls waiting for approval keep their own ids and resume`() = runTest {
        val tools = listOf(
            tool("write", needsApproval = true) { listOf(UIMessagePart.Text("written")) },
            tool("echo") { listOf(UIMessagePart.Text(it.query())) },
        )
        provider.result(
            assistant(
                UIMessagePart.Tool("", "write", """{"q":"w"}"""),
                UIMessagePart.Tool("", "echo", """{"q":"e"}"""),
            )
        )

        val waiting = runToEnd(request(tools = tools))
        val reason = waiting.reason as AgentFinishReason.AwaitingApproval
        val waitingCalls = waiting.message.getTools()
        assertEquals(2, waitingCalls.size)
        assertEquals(2, waitingCalls.map { it.toolCallId }.toSet().size)
        // 审批挂在具体的那一个调用上，不会把同批的另一个也算进去
        assertEquals(setOf(waitingCalls.first().toolCallId), reason.toolCallIds)
        assertFalse(waitingCalls.any { it.isExecuted })

        provider.result(assistant(UIMessagePart.Text("done")))
        val approved = waiting.message.withApproval(waitingCalls.first().toolCallId, ToolApprovalState.Approved)
        val finished = runToEnd(request(messages = listOf(UIMessage.user("hi"), approved), tools = tools))

        val resumed = finished.message.getTools()
        assertEquals(waitingCalls.map { it.toolCallId }, resumed.map { it.toolCallId })
        assertEquals(listOf("written", "e"), resumed.map { it.outputText() })
    }

    @Test
    fun `resuming a call that never ran keeps its id and renames the model's duplicate`() = runTest {
        val executed = mutableListOf<String>()
        val tools = listOf(
            tool("echo") { args ->
                executed += args.query()
                listOf(UIMessagePart.Text(args.query()))
            }
        )
        val interrupted = assistant(
            UIMessagePart.Tool("call-1", "echo", """{"q":"first"}""", output = listOf(UIMessagePart.Text("first"))),
            UIMessagePart.Tool("call-2", "echo", """{"q":"second"}""", approvalState = ToolApprovalState.Approved),
        )
        provider.result(assistant(UIMessagePart.Tool("call-2", "echo", """{"q":"third"}""")))
        provider.result(assistant(UIMessagePart.Text("done")))

        val message = runToEnd(request(messages = listOf(UIMessage.user("hi"), interrupted), tools = tools)).message

        val calls = message.getTools()
        assertEquals(3, calls.size)
        // 取消时没跑的那个调用不需要改 id，照原样补跑
        assertEquals(listOf("call-1", "call-2"), calls.take(2).map { it.toolCallId })
        assertEquals(listOf("first", "second", "third"), calls.map { it.outputText() })
        assertEquals(3, calls.map { it.toolCallId }.toSet().size)
        assertEquals(listOf("second", "third"), executed)
    }
}

/** 按调用顺序返回预设的非流式响应，并记录每次请求 */
private class NonStreamIdsProvider : Provider<ProviderSetting> {
    data class Request(val messages: List<UIMessage>, val params: TextGenerationParams)

    val requests = mutableListOf<Request>()
    private val results = ArrayDeque<TextGenerationResult>()

    fun result(message: UIMessage, usage: TokenUsage? = null) {
        results += TextGenerationResult(id = "result", model = "test-model", message = message, usage = usage)
    }

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        requests += Request(messages, params)
        return results.removeFirst()
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = error("these tests run without streaming")
}

private class NonStreamIdsHooks(private val model: Model) : AgentHooks {
    override suspend fun prepareStep(messages: List<UIMessage>): StepRequest =
        StepRequest(messages, TextGenerationParams(model = model))
}

private fun assistant(vararg parts: UIMessagePart) =
    UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

private fun tool(
    name: String,
    needsApproval: Boolean = false,
    execute: suspend (JsonElement) -> List<UIMessagePart> = { listOf(UIMessagePart.Text("ok")) },
) = Tool(name = name, description = name, needsApproval = { needsApproval }, execute = execute)

private val echo = tool("echo") { args -> listOf(UIMessagePart.Text(args.query())) }

private fun JsonElement.query(): String = jsonObject["q"]?.jsonPrimitive?.content.orEmpty()

private fun UIMessagePart.Tool.query(): String = inputAsJson().query()

private fun UIMessagePart.Tool.outputText(): String =
    output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

private fun UIMessage.withApproval(id: String, state: ToolApprovalState) = copy(
    parts = parts.map { part ->
        if (part is UIMessagePart.Tool && part.toolCallId == id) part.copy(approvalState = state) else part
    }
)
