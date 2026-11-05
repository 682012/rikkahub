package me.rerere.ai.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ToolInputState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数没收到完整的工具调用**绝不执行**，恢复时也不会被自动重放。
 *
 * 场景对应真实事故：模型正在流式发一个写操作的工具参数时断线/被取消，半截 JSON 留在消息里，
 * 用户接着让模型继续说，或者点"继续生成"，这条调用不能再跑一遍。
 */
class ToolInputCompletenessTest {
    private val model = Model(modelId = "test-model")
    private val provider = IncompleteInputProvider()
    private val hooks = IncompleteInputHooks(model)

    private fun request(
        messages: List<UIMessage>,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 16,
    ) = AgentRequest(
        provider = provider,
        providerSetting = ProviderSetting.OpenAI(),
        messages = messages,
        tools = tools,
        stream = true,
        maxSteps = maxSteps,
        retry = RetryPolicy.NONE,
    )

    @Test
    fun `resuming with half streamed arguments never runs the tool`() = runTest {
        var executed = 0
        val tools = listOf(
            Tool(
                name = "write",
                description = "write",
                execute = {
                    executed++
                    listOf(UIMessagePart.Text("written"))
                },
            )
        )
        val interrupted = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "write",
                    input = HALF_JSON,
                    inputState = ToolInputState.INTERRUPTED,
                )
            ),
        )
        provider.stream {
            emit(StreamChunk.TextStart("text"))
            emit(StreamChunk.TextDelta("text", "answer"))
            emit(StreamChunk.TextEnd("text"))
            emit(StreamChunk.Finish(finishReason = "stop"))
        }

        val events = runAgent(
            request(messages = listOf(UIMessage.user("hi"), interrupted), tools = tools),
            hooks,
        ).toList()

        val finished = events.last() as AgentEvent.Finished
        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertEquals(0, executed)

        val part = finished.message.getTools().single { it.toolCallId == "call-1" }
        // 半截参数原文原样保留，状态也不会被"恢复"洗成完整
        assertEquals(HALF_JSON, part.input)
        assertEquals(ToolInputState.INTERRUPTED, part.inputState)
        assertFalse(part.isInputRunnable)
        // 但必须有一条"未执行"的结果，否则下一次请求里这个 tool_call 没有结果、wire 协议不合法
        assertTrue(part.isExecuted)
        assertTrue(part.joinedOutputText().contains("not executed"))
        // 模型确实被再问了一次，能自己重新发一个完整调用
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun `legacy record with broken json is not executed either`() = runTest {
        var executed = 0
        val tools = listOf(
            Tool(
                name = "write",
                description = "write",
                execute = {
                    executed++
                    listOf(UIMessagePart.Text("written"))
                },
            )
        )
        // 旧记录没有 inputState，缺省是 COMPLETE，但原文就是半截 JSON
        val legacy = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "write",
                    input = HALF_JSON,
                )
            ),
        )
        provider.stream {
            emit(StreamChunk.TextStart("text"))
            emit(StreamChunk.TextDelta("text", "answer"))
            emit(StreamChunk.TextEnd("text"))
            emit(StreamChunk.Finish(finishReason = "stop"))
        }

        val finished = runAgent(
            request(messages = listOf(UIMessage.user("hi"), legacy), tools = tools),
            hooks,
        ).toList().last() as AgentEvent.Finished

        assertEquals(0, executed)
        val part = finished.message.getTools().single { it.toolCallId == "call-1" }
        assertEquals(HALF_JSON, part.input)
        assertTrue(part.joinedOutputText().contains("not executed"))
    }

    @Test
    fun `complete arguments still go through the normal execution path`() = runTest {
        var executed = 0
        val tools = listOf(
            Tool(
                name = "write",
                description = "write",
                execute = {
                    executed++
                    listOf(UIMessagePart.Text("written"))
                },
            )
        )
        // Provider 正常发完参数：必须照旧执行，不能因为新增的完整度检查把 provider 工具一刀切停掉
        provider.stream {
            emit(StreamChunk.ToolCallStart("call-1", "write"))
            emit(StreamChunk.ToolCallDelta("call-1", inputDelta = FULL_JSON))
            emit(StreamChunk.ToolCallEnd("call-1"))
            emit(StreamChunk.Finish(finishReason = "tool_calls"))
        }
        provider.stream {
            emit(StreamChunk.TextStart("text"))
            emit(StreamChunk.TextDelta("text", "done"))
            emit(StreamChunk.TextEnd("text"))
            emit(StreamChunk.Finish(finishReason = "stop"))
        }

        val finished = runAgent(
            request(messages = listOf(UIMessage.user("hi")), tools = tools),
            hooks,
        ).toList().last() as AgentEvent.Finished

        assertEquals(1, executed)
        val part = finished.message.getTools().single { it.toolCallId == "call-1" }
        assertEquals(ToolInputState.COMPLETE, part.inputState)
        assertEquals("written", part.joinedOutputText())
    }

    @Test
    fun `a truncated stream never executes the tool and keeps the partial input`() = runTest {
        var executed = 0
        val tools = listOf(
            Tool(
                name = "write",
                description = "write",
                execute = {
                    executed++
                    listOf(UIMessagePart.Text("written"))
                },
            )
        )
        provider.stream {
            emit(StreamChunk.ToolCallStart("call-1", "write"))
            emit(StreamChunk.ToolCallDelta("call-1", inputDelta = TRUNCATED_BUT_PARSABLE))
            // max_tokens 截断：流正常收尾，但参数其实没发完
            emit(StreamChunk.Finish(finishReason = "max_tokens"))
        }
        provider.stream {
            emit(StreamChunk.TextStart("text"))
            emit(StreamChunk.TextDelta("text", "answer"))
            emit(StreamChunk.TextEnd("text"))
            emit(StreamChunk.Finish(finishReason = "stop"))
        }

        val finished = runAgent(
            request(messages = listOf(UIMessage.user("hi")), tools = tools),
            hooks,
        ).toList().last() as AgentEvent.Finished

        assertEquals(0, executed)
        val part = finished.message.getTools().single { it.toolCallId == "call-1" }
        assertEquals(TRUNCATED_BUT_PARSABLE, part.input)
        assertEquals(ToolInputState.INTERRUPTED, part.inputState)
    }

    @Test
    fun `cancelling while arguments stream keeps the partial input`() = runTest {
        val emitted = CompletableDeferred<Unit>()
        provider.stream {
            emit(StreamChunk.ToolCallStart("call-1", "write"))
            emit(StreamChunk.ToolCallDelta("call-1", inputDelta = HALF_JSON))
            emitted.complete(Unit)
            awaitCancellation()
        }

        val events = mutableListOf<AgentEvent>()
        val job = launch { runAgent(request(messages = listOf(UIMessage.user("hi"))), hooks).toList(events) }
        emitted.await()
        job.cancelAndJoin()

        val shown = events.filterIsInstance<AgentEvent.MessageUpdated>().last().message
        val part = shown.getTools().single { it.toolCallId == "call-1" }
        // 用户点了停止：已经生成的那半截参数必须还在，可持久化，并且绝不能被当成完整参数
        assertEquals(HALF_JSON, part.input)
        assertFalse(part.isExecuted)
        assertFalse(part.isInputRunnable)
        assertNotEquals(ToolInputState.COMPLETE, part.inputState)
    }

    private companion object {
        const val HALF_JSON = """{"path":"/da"""

        const val FULL_JSON = """{"path":"/data"}"""

        const val TRUNCATED_BUT_PARSABLE = """{"path":"/data","opts":{}}"""
    }
}

private class IncompleteInputProvider : Provider<ProviderSetting> {
    val requests = mutableListOf<List<UIMessage>>()
    private val streams = ArrayDeque<suspend FlowCollector<StreamChunk>.() -> Unit>()

    fun stream(block: suspend FlowCollector<StreamChunk>.() -> Unit) {
        streams += block
    }

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = error("non-stream generation is not used in these tests")

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        requests += messages
        val block = streams.removeFirst()
        return flow { block() }
    }
}

private class IncompleteInputHooks(private val model: Model) : AgentHooks {
    override suspend fun prepareStep(messages: List<UIMessage>) =
        StepRequest(messages, TextGenerationParams(model = model))
}

private fun UIMessagePart.Tool.joinedOutputText() =
    output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
