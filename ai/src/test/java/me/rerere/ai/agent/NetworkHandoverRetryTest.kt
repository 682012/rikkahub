package me.rerere.ai.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.NetworkHandover
import me.rerere.common.http.NetworkRouteState
import me.rerere.common.http.RouteWaiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * r3 网络恢复：退避遇到网络代际变化不要干等，以及重试期间界面预览不缩水。
 *
 * 用真实的 [NetworkHandover]，只把它的等待原语换成 [RecordingWaiter]，
 * 所有等待都跑在 [runTest] 的虚拟时间里：不碰真网络、不用 Thread.sleep。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkHandoverRetryTest {
    private val model = Model(modelId = "test-model")
    private val provider = HandoverFakeProvider()
    private val hooks = HandoverHooks(model)

    private fun policy(
        network: NetworkHandover?,
        delayMode: RetryDelay = RetryDelay.Exponential,
        maxRetries: Int = 3,
        retryOn: (Throwable) -> Boolean = { it is IOException },
    ) = RetryPolicy(
        maxRetries = maxRetries,
        retryOn = retryOn,
        delayMode = delayMode,
        networkHandover = network,
    )

    private fun request(
        messages: List<UIMessage> = listOf(UIMessage.user("hi")),
        tools: List<Tool> = emptyList(),
        retry: RetryPolicy = RetryPolicy.NONE,
    ) = AgentRequest(
        provider = provider,
        providerSetting = ProviderSetting.OpenAI(),
        messages = messages,
        tools = tools,
        stream = true,
        retry = retry,
    )

    // 一步结束前只发预览，最后一个 MessageUpdated 才是这一步的最终结果
    private fun List<AgentEvent>.previews() =
        filterIsInstance<AgentEvent.MessageUpdated>().dropLast(1).map { it.message.texts() }

    private fun List<AgentEvent>.updates() =
        filterIsInstance<AgentEvent.MessageUpdated>().map { it.message.texts() }

    private fun List<AgentEvent>.finishedEvent() = last() as AgentEvent.Finished

    // ---- 退避与网络代际 ----

    @Test
    fun `epoch change during the request skips the 60s backoff`() = runTest {
        val waiter = RecordingWaiter()
        // 初代网络：epoch 0，可用
        val network = NetworkHandover(waiter).apply { updateRoute("wifi", true) }
        var calls = 0
        val attempts = mutableListOf<RetryAttempt>()

        val result = withRetry(
            policy(network, RetryDelay.fixed(60)),
            onRetry = { attempts += it },
        ) {
            calls++
            if (calls == 1) {
                // 请求还没回来就切了 VPN：代际 +1，这次连接被重置
                network.updateRoute("cell", true)
                throw IOException("connection reset")
            }
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, calls)
        // 用户设置的 60s 退避没有变，只是这次不用等满
        assertEquals(listOf(60.seconds), attempts.map { it.delay })
        assertEquals(listOf(NetworkHandover.STABILIZE_MILLIS), waiter.sleeps)
        // 只等了新网络的稳定期
        assertEquals(NetworkHandover.STABILIZE_MILLIS, currentTime)
    }

    @Test
    fun `offline wait ends soon after the network comes back`() = runTest {
        val waiter = RecordingWaiter()
        // 同一个网络但离线：代际不变
        val network = NetworkHandover(waiter).apply { updateRoute("wifi", false) }
        var calls = 0

        val job = launch {
            withRetry(policy(network, RetryDelay.fixed(60))) {
                calls++
                if (calls == 1) throw IOException("offline")
                "ok"
            }
        }

        waiter.awaitEntered()
        // 离线等了 30s 还没恢复
        advanceTimeBy(30.seconds)
        assertEquals(30_000L, currentTime)
        assertEquals(1, calls)

        // 断网恢复：立刻醒，再等一小段稳定期
        network.updateRoute("wifi", true)
        job.join()

        assertEquals(2, calls)
        assertEquals(30_000L + NetworkHandover.STABILIZE_MILLIS, currentTime)
        assertTrue("不该把 60s 等满", currentTime < 60_000L)
    }

    @Test
    fun `backoff waits the configured delay when the network does not change`() = runTest {
        val waiter = RecordingWaiter()
        val network = NetworkHandover(waiter).apply { updateRoute("wifi", true) }
        var calls = 0

        val job = launch {
            withRetry(policy(network, RetryDelay.fixed(60))) {
                calls++
                if (calls == 1) throw IOException("reset")
                "ok"
            }
        }

        waiter.awaitEntered()
        job.join()

        assertEquals(2, calls)
        // 没有网络事件时完全按设置的 60s 等
        assertEquals(60_000L, currentTime)
        assertEquals(listOf(60_000L), waiter.sleeps)
    }

    @Test
    fun `user cancel during the backoff wait never restarts the request`() = runTest {
        val waiter = RecordingWaiter()
        val network = NetworkHandover(waiter).apply { updateRoute("wifi", true) }
        var calls = 0

        val job = launch {
            withRetry(policy(network, RetryDelay.fixed(60))) {
                calls++
                throw IOException("reset")
            }
        }

        waiter.awaitEntered()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(1, calls)
    }

    @Test
    fun `auth errors are not retried even with the network watcher attached`() = runTest {
        val waiter = RecordingWaiter()
        val network = NetworkHandover(waiter).apply { updateRoute("wifi", true) }
        var calls = 0

        val error = runCatching {
            withRetry(
                policy(network, retryOn = { it is IOException && !it.message.orEmpty().contains("401") }),
            ) {
                calls++
                network.updateRoute("cell", true)
                throw IOException("401 unauthorized")
            }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(1, calls)
        // 一次退避都没进：网络变了也不会把不该重试的错误变成重试
        assertTrue(waiter.sleeps.isEmpty())
    }

    @Test
    fun `max retries still bounds the attempts when the network keeps changing`() = runTest {
        val waiter = RecordingWaiter()
        val network = NetworkHandover(waiter).apply { updateRoute("wifi", true) }
        var calls = 0

        val error = runCatching {
            withRetry(policy(network, RetryDelay.fixed(60), maxRetries = 2)) {
                calls++
                // 每次尝试都赶上换网，退避都被缩短，但次数仍受 maxRetries 限制
                network.updateRoute("net-$calls", true)
                throw IOException("reset")
            }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        // 1 次首发 + 2 次重试，网络再怎么变也不会无限重试
        assertEquals(3, calls)
        assertEquals(List(2) { NetworkHandover.STABILIZE_MILLIS }, waiter.sleeps)
    }

    // ---- agent 循环里的工具与预览 ----

    @Test
    fun `tool calls of a failed attempt are never executed`() = runTest {
        val network = NetworkHandover(RecordingWaiter()).apply { updateRoute("wifi", true) }
        val executed = mutableListOf<String>()
        val tools = listOf(handoverTool("search") {
            executed += "search"
            listOf(UIMessagePart.Text("ok"))
        })
        // 第一次尝试先吐出工具调用再断开
        provider.stream {
            toolCallChunk("call-failed", "search")
            throw IOException("connection reset")
        }
        // 重试给出一个正常的工具调用
        provider.stream {
            toolCallChunk("call-1", "search")
            finishChunk()
        }
        provider.stream {
            textOf("done")
            finishChunk()
        }

        val message = runAgent(request(tools = tools, retry = policy(network)), hooks)
            .toList()
            .finishedEvent()
            .message

        // 只执行了成功那次尝试给出的工具
        assertEquals(listOf("search"), executed)
        assertEquals(1, message.getTools().size)
        assertEquals("call-1", message.getTools().single().toolCallId)
        assertTrue(message.getTools().single().isExecuted)
        assertEquals(listOf("done"), message.texts())
        assertEquals(3, provider.requests.size)
    }

    @Test
    fun `retry keeps the shown preview and catches up instead of shrinking`() = runTest {
        val network = NetworkHandover(RecordingWaiter()).apply { updateRoute("wifi", true) }
        // 第一次尝试展示到 "Hello" 后断开
        provider.stream {
            emitTextDelta("He")
            emitTextDelta("llo")
            throw IOException("connection reset")
        }
        // 重试的内容严格延续旧文本：先追上已展示的内容，再继续往后加
        provider.stream {
            emitTextDelta("He")
            emitTextDelta("llo")
            emitTextDelta(" world")
            finishChunk()
        }

        val events = runAgent(request(retry = policy(network)), hooks).toList()

        assertEquals(listOf("Hello world"), events.finishedEvent().message.texts())
        val previews = events.previews().map { it.single() }
        // 重试过程中上报的文本只增长，不会先缩回半截再重画
        assertEquals(previews, previews.sortedWith(compareBy { it.length }))
        assertTrue(previews.zipWithNext().all { (previous, next) -> next.startsWith(previous) })
        // 追上之后继续刷新，不是等最后一步才一次性替换
        assertTrue(previews.contains("Hello"))
        assertTrue(previews.size > 3)
    }

    @Test
    fun `successful retry with different content replaces the preview as a whole`() = runTest {
        val network = NetworkHandover(RecordingWaiter()).apply { updateRoute("wifi", true) }
        provider.stream {
            emitTextDelta("par")
            emitTextDelta("tial")
            throw IOException("connection reset")
        }
        // 重试换了内容，长度也超过已展示的那一段
        provider.stream {
            emitTextDelta("differ")
            emitTextDelta("ent")
            emitTextDelta(" ending")
            finishChunk()
        }

        val events = runAgent(request(retry = policy(network)), hooks).toList()

        assertEquals(listOf("different ending"), events.finishedEvent().message.texts())
        // 已展示的 "partial" 在重试期间保持不动，成功后才整体替换
        assertEquals(listOf(listOf("par"), listOf("partial")), events.previews())
        assertTrue(events.previews().none { it.single().contains("different") })
        // 两次生成的内容不会拼在一起
        assertTrue(events.updates().none { it.single() == "partialdifferent ending" })
    }

    @Test
    fun `a run that finally fails keeps the last preview instead of clearing it`() = runTest {
        provider.stream {
            emitTextDelta("par")
            emitTextDelta("tial")
            throw IOException("connection reset")
        }
        provider.stream { throw IOException("still offline") }

        val events = mutableListOf<AgentEvent>()
        val error = runCatching {
            runAgent(request(retry = policy(null, maxRetries = 1)), hooks).toList(events)
        }.exceptionOrNull()

        assertTrue(error is IOException)
        // 最终失败时界面保留上一次的预览，最后一个 MessageUpdated 仍是那段内容
        assertEquals(listOf(listOf("par"), listOf("partial")), events.updates())
    }
}

/** 把 [NetworkHandover] 的等待换成虚拟时间，并记下每次实际等待的长度 */
private class RecordingWaiter : RouteWaiter {
    private val entries = Channel<Unit>(Channel.UNLIMITED)
    private var elapsedMillis = 0L

    /** 每次真正挂起等待的毫秒数 */
    val sleeps = mutableListOf<Long>()

    /** 等到等待原语真的被调用了一次 */
    suspend fun awaitEntered() = entries.receive()

    override suspend fun awaitAvailable(
        state: StateFlow<NetworkRouteState>,
        timeoutMillis: Long,
    ): Boolean {
        entries.send(Unit)
        if (timeoutMillis <= 0L) return state.value.available
        return withTimeoutOrNull(timeoutMillis) {
            state.first { it.available }
            true
        } ?: false
    }

    override suspend fun sleep(millis: Long) {
        entries.send(Unit)
        sleeps += millis
        if (millis > 0L) delay(millis)
        elapsedMillis += millis
    }

    override fun nowMillis(): Long = elapsedMillis
}

/** 按调用顺序依次返回预设的流式响应，并记录每次请求 */
private class HandoverFakeProvider : Provider<ProviderSetting> {
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
    ): TextGenerationResult = error("this test only uses streaming")

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

private class HandoverHooks(private val model: Model) : AgentHooks {
    override suspend fun prepareStep(messages: List<UIMessage>): StepRequest =
        StepRequest(messages, TextGenerationParams(model = model))
}

private suspend fun FlowCollector<StreamChunk>.emitTextStart(id: String = "text") =
    emit(StreamChunk.TextStart(id))

private suspend fun FlowCollector<StreamChunk>.emitTextDelta(text: String, id: String = "text") =
    emit(StreamChunk.TextDelta(id, text))

private suspend fun FlowCollector<StreamChunk>.emitTextEnd(id: String = "text") =
    emit(StreamChunk.TextEnd(id))

private suspend fun FlowCollector<StreamChunk>.textOf(text: String, id: String = "text") {
    emitTextStart(id)
    emitTextDelta(text, id)
    emitTextEnd(id)
}

private suspend fun FlowCollector<StreamChunk>.toolCallChunk(
    id: String,
    name: String,
    input: String = "{}",
) {
    emit(StreamChunk.ToolCallStart(id, name))
    emit(StreamChunk.ToolCallDelta(id, inputDelta = input))
    emit(StreamChunk.ToolCallEnd(id))
}

private suspend fun FlowCollector<StreamChunk>.finishChunk() {
    emit(StreamChunk.Finish(finishReason = "stop"))
}

private fun handoverTool(
    name: String,
    execute: suspend (JsonElement) -> List<UIMessagePart>,
) = Tool(name = name, description = name, needsApproval = { false }, execute = execute)

private fun UIMessage.texts() = parts.filterIsInstance<UIMessagePart.Text>().map { it.text }
