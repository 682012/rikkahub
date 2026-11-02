package me.rerere.ai.agent

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelRequestLimiter
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestLimitedProvider
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * `runAgent` + [RequestLimitedProvider] + [ModelRequestLimiter] 的真实链路回归：同一个 [Model.id] 限额 1 时，
 * 父代理多步循环里的工具再去发一次请求（子代理场景），不能和父代理这一步抢名额而互相卡死。
 *
 * 已有的 provider 层测试只单独验证"工具间隙不占名额"这个前提，不经过 agent 循环；这里全部走真实的
 * [runAgent]：父代理第一轮返回 tool call，工具体内用同一个 provider 实例发子代理请求，父代理再继续第二轮。
 *
 * 时间一律用 runTest 的虚拟调度器和 [withTimeoutOrNull]；卡死会表现为超时（虚拟时间立即推进），不用 sleep。
 */
class ModelRequestConcurrencyIntegrationTest {
    private val model = Model(
        modelId = "limited-model",
        displayName = "Limited",
        id = Uuid.random(),
        maxConcurrentRequests = 1,
    )
    private val setting = ProviderSetting.OpenAI()

    @Test
    fun `tool issuing its own request on the same model does not deadlock at limit 1`() = runTest {
        val limiter = ModelRequestLimiter()
        val api = RecordingApi(limiter, model)
        // 父代理和"子代理"必须是同一个限流实例、同一个 Model，才谈得上抢名额
        val provider = RequestLimitedProvider(api, limiter)

        // 父代理第一轮：要求调用工具；第二轮：给出答案
        api.stream {
            toolCall("call-1", "delegate", """{"task":"sub"}""")
            finish()
        }
        api.stream {
            text("answer")
            finish()
        }
        // 工具体内那一次子代理请求的返回
        api.result("sub-agent")

        var inFlightAtToolStart = -1
        var inFlightAfterNested = -1
        var nestedText: String? = null
        val delegate = Tool(
            name = "delegate",
            description = "runs a sub-agent request on the same model",
            execute = {
                // 父代理这一步已经结束，名额必须已经归还，否则下面这次请求在限额 1 下永远拿不到
                inFlightAtToolStart = limiter.inFlightCount(model.id)
                api.events += "tool-start"
                val nested = withTimeoutOrNull(30.seconds) {
                    provider.generateText(
                        providerSetting = setting,
                        messages = listOf(UIMessage.user("sub task")),
                        params = TextGenerationParams(model = model),
                    )
                }
                assertNotNull("the sub-agent request never got a permit at limit 1", nested)
                nestedText = nested?.message?.parts?.filterIsInstance<UIMessagePart.Text>()
                    ?.joinToString("") { it.text }
                inFlightAfterNested = limiter.inFlightCount(model.id)
                api.events += "tool-end"
                listOf(UIMessagePart.Text("sub=$nestedText"))
            },
        )

        val events = runAgent(
            AgentRequest(
                provider = provider,
                providerSetting = setting,
                messages = listOf(UIMessage.user("hi")),
                tools = listOf(delegate),
                stream = true,
            ),
            hooks = ModelParamsHooks(model),
        ).toList()

        val finished = events.last() as AgentEvent.Finished
        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertEquals("answer", finished.message.texts())

        // 子代理请求没被父代理的名额堵住
        assertEquals("sub-agent", nestedText)
        assertEquals("sub=sub-agent", finished.message.getTools().single().outputText())
        assertEquals(0, inFlightAtToolStart)
        assertEquals(0, inFlightAfterNested)

        // 父代理两轮 + 子代理一次，且真正打到 API 的并发峰值是 1
        assertEquals(2, api.streamCalls)
        assertEquals(1, api.generateCalls)
        assertEquals(1, api.peak)
        assertEquals(listOf(1, 1, 1), api.gateCountsAtApiEntry)

        // 工具在父代理第一步的流结束之后才跑，父代理第二步的请求在工具之后才发
        api.events.assertOrder("stream#1-end", "tool-start", "generate#1-start", "tool-end", "stream#2-start")

        assertNoLeaseLeft(limiter)
    }

    @Test
    fun `streaming parent holds the slot only until upstream ends and the tool runs with an empty gate`() = runTest {
        val limiter = ModelRequestLimiter()
        val api = RecordingApi(limiter, model)
        val provider = RequestLimitedProvider(api, limiter)

        api.stream {
            toolCall("call-1", "inspect", "{}")
            finish()
        }
        api.stream {
            text("done")
            finish()
        }

        var inFlightAtToolStart = -1
        val inspect = Tool(
            name = "inspect",
            description = "reads the gate while the agent loop runs tools",
            execute = {
                inFlightAtToolStart = limiter.inFlightCount(model.id)
                api.events += "tool-start"
                listOf(UIMessagePart.Text("gate=$inFlightAtToolStart"))
            },
        )

        val events = runAgent(
            AgentRequest(
                provider = provider,
                providerSetting = setting,
                messages = listOf(UIMessage.user("hi")),
                tools = listOf(inspect),
                stream = true,
            ),
            hooks = ModelParamsHooks(model),
        ).toList()

        val finished = events.last() as AgentEvent.Finished
        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertEquals("done", finished.message.texts())

        // 名额跟着上游 collect 走：上游自己结束时仍持有，工具执行时已归还
        assertEquals(listOf(1, 1), api.gateCountsAtStreamEnd)
        assertEquals(listOf(1, 1), api.gateCountsAtApiEntry)
        assertEquals(0, inFlightAtToolStart)
        assertEquals(1, api.peak)
        api.events.assertOrder("stream#1-end", "tool-start", "stream#2-start")

        assertNoLeaseLeft(limiter)
    }

    @Test
    fun `a failed attempt frees the slot so another request can run during the retry backoff`() = runTest {
        val limiter = ModelRequestLimiter()
        val api = RecordingApi(limiter, model)
        val provider = RequestLimitedProvider(api, limiter)
        val retry = RetryPolicy(maxRetries = 1, delayMode = RetryDelay.Fixed(5.seconds))

        // 第一轮流到一半断线，重试时重新发一次请求
        api.stream {
            text("half")
            throw IOException("connection reset")
        }
        api.stream {
            text("recovered")
            finish()
        }
        // 退避期间另一个请求要能进来
        api.result("other-window")

        var inFlightWhenRetrying = -1
        var otherResult: String? = null
        var retrySignalled = false
        val seen = mutableListOf<AgentEvent>()

        coroutineScope {
            runAgent(
                AgentRequest(
                    provider = provider,
                    providerSetting = setting,
                    messages = listOf(UIMessage.user("hi")),
                    retry = retry,
                ),
                hooks = ModelParamsHooks(model),
            ).collect { event ->
                seen += event
                if (event is AgentEvent.Retrying) {
                    retrySignalled = true
                    // 失败那一刻名额必须已经归还，否则退避期间谁也别想进来
                    inFlightWhenRetrying = limiter.inFlightCount(model.id)
                    api.events += "retry-signal"
                    launch {
                        val other = withTimeoutOrNull(30.seconds) {
                            provider.generateText(
                                providerSetting = setting,
                                messages = listOf(UIMessage.user("other")),
                                params = TextGenerationParams(model = model),
                            )
                        }
                        otherResult = other?.message?.parts?.filterIsInstance<UIMessagePart.Text>()
                            ?.joinToString("") { it.text }
                        api.events += "other-done"
                    }
                }
            }
        }

        assertTrue("retry was never signalled", retrySignalled)
        assertEquals(0, inFlightWhenRetrying)
        assertEquals("other-window", otherResult)
        // 重试之后父代理正常收尾，说明失败的尝试没有把名额或循环卡住
        val finished = seen.last() as AgentEvent.Finished
        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertEquals("recovered", finished.message.texts())

        assertEquals(2, api.streamCalls)
        assertEquals(1, api.generateCalls)
        assertEquals(1, api.peak)
        // 另一个请求在重试的第二次尝试之前就拿到名额并跑完了
        api.events.assertOrder("retry-signal", "generate#1-start", "other-done", "stream#2-start")

        assertNoLeaseLeft(limiter)
    }

    private fun assertNoLeaseLeft(limiter: ModelRequestLimiter) {
        assertEquals(0, limiter.inFlightCount(model.id))
        assertEquals(0, limiter.waitingCount(model.id))
        // 空的桶会被回收，跑完不留任何状态
        assertEquals(0, limiter.trackedModelCount())
    }
}

/**
 * 真实 Provider 的替身：只有 [RequestLimitedProvider] 放行之后才会进到这里，
 * 所以这里数出来的并发就是"真正打到 API 的并发"，同时把闸门内部计数一起记下来做交叉验证。
 */
private class RecordingApi(
    private val limiter: ModelRequestLimiter,
    private val model: Model,
) : Provider<ProviderSetting> {
    /** 进入/离开 API 的事件顺序 */
    val events = mutableListOf<String>()

    /** 每次真正进入 API 时闸门里的在途名额数 */
    val gateCountsAtApiEntry = mutableListOf<Int>()

    /** 上游流自己结束时闸门里的在途名额数（此时应该还持有） */
    val gateCountsAtStreamEnd = mutableListOf<Int>()

    var peak = 0
        private set

    var streamCalls = 0
        private set

    var generateCalls = 0
        private set

    private var active = 0
    private val streams = ArrayDeque<suspend FlowCollector<StreamChunk>.() -> Unit>()
    private val results = ArrayDeque<TextGenerationResult>()

    fun stream(block: suspend FlowCollector<StreamChunk>.() -> Unit) {
        streams += block
    }

    fun result(text: String) {
        results += TextGenerationResult(
            id = "result",
            model = model.modelId,
            message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text))),
        )
    }

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val call = ++generateCalls
        enter("generate#$call")
        try {
            return results.removeFirst()
        } finally {
            exit("generate#$call")
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        val call = ++streamCalls
        enter("stream#$call")
        try {
            streams.removeFirst().invoke(this)
            gateCountsAtStreamEnd += limiter.inFlightCount(model.id)
            events += "stream#$call-end"
        } finally {
            exit("stream#$call")
        }
    }

    private fun enter(tag: String) {
        active++
        if (active > peak) peak = active
        gateCountsAtApiEntry += limiter.inFlightCount(model.id)
        events += "$tag-start"
    }

    private fun exit(tag: String) {
        active--
        events += "$tag-exit"
    }
}

private class ModelParamsHooks(private val model: Model) : AgentHooks {
    override suspend fun prepareStep(messages: List<UIMessage>) =
        StepRequest(messages, TextGenerationParams(model = model))
}

private suspend fun FlowCollector<StreamChunk>.text(value: String, id: String = "text") {
    emit(StreamChunk.TextStart(id))
    emit(StreamChunk.TextDelta(id, value))
    emit(StreamChunk.TextEnd(id))
}

private suspend fun FlowCollector<StreamChunk>.toolCall(id: String, name: String, input: String = "{}") {
    emit(StreamChunk.ToolCallStart(id, name))
    emit(StreamChunk.ToolCallDelta(id, inputDelta = input))
    emit(StreamChunk.ToolCallEnd(id))
}

private suspend fun FlowCollector<StreamChunk>.finish() {
    emit(StreamChunk.Finish(finishReason = "stop"))
}

private fun UIMessage.texts() =
    parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

private fun UIMessagePart.Tool.outputText() =
    output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

private fun List<String>.assertOrder(vararg expected: String) {
    val indexes = expected.map { label ->
        val index = indexOf(label)
        assertTrue("missing event $label in $this", index >= 0)
        index
    }
    assertEquals("events out of order: $this", indexes.sorted(), indexes)
}
