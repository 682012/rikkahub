package me.rerere.ai.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant

/**
 * r22 执行调度：便宜的分组键、事件合并。
 *
 * 只验证两件能测出来的事：
 * 1. 名字在一个并行窗口里只出现一次的调用不再做代价最高的规范化参数（groupParallelCalls）；
 * 2. 同一个进度事件里的多个调用只提交一次消息更新（对应调用方一次落库），
 *    但 startedAt 仍然在效果之前落地、每个调用仍然有自己的结果。
 *
 * 计时用的是真实 CPU 时间，只用来给同一次运行里的两种写法对比，不作为对外性能指标。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R22ExecutionSchedulingTest {
    private val model = Model(modelId = "r22-model")

    // ------------------------------------------------------------ 分组键

    @Test
    fun `unique tool names are never canonicalized`() {
        var canonicalized = 0
        val calls = listOf(
            r22Call("call-1", "write_file", "{\"path\":\"a\",\"content\":\"...\"}"),
            r22Call("call-2", "apply_patch", "{\"diff\":\"...\"}"),
            r22Call("call-3", "read_file", "{\"path\":\"c\"}"),
        )
        val groups = r22Group(calls) {
            canonicalized++
            canonicalToolArguments(it.input) ?: it.input
        }

        assertEquals("名字只出现一次时不可能撞组，不该付规范化参数的代价", 0, canonicalized)
        assertEquals(3, groups.size)
        assertEquals(listOf("call-1", "call-2", "call-3"), groups.flatten().map { it.toolCallId })
    }

    @Test
    fun `repeated names are canonicalized and equal arguments still collapse`() {
        var canonicalized = 0
        val calls = listOf(
            // 键顺序不同，规范化后相同
            r22Call("call-1", "read_file", "{\"path\":\"a\",\"page\":1}"),
            r22Call("call-2", "read_file", "{\"page\":1,\"path\":\"a\"}"),
            r22Call("call-3", "read_file", "{\"path\":\"b\"}"),
        )
        val groups = r22Group(calls) {
            canonicalized++
            canonicalToolArguments(it.input) ?: it.input
        }

        assertEquals("同名出现三次，三个都要规范化", 3, canonicalized)
        assertEquals(2, groups.size)
        assertEquals(listOf("call-1", "call-2"), groups[0].map { it.toolCallId })
        assertEquals(listOf("call-3"), groups[1].map { it.toolCallId })
    }

    @Test
    fun `non deduplicated calls stay separate without canonicalization`() {
        var canonicalized = 0
        val calls = listOf(
            r22Call("call-1", "search", "{\"q\":\"x\"}"),
            r22Call("call-2", "search", "{\"q\":\"x\"}"),
        )
        val groups = groupParallelCalls(
            calls = calls,
            nameOf = { it.toolName },
            deduplicated = { false },
            canonicalKey = {
                canonicalized++
                it.input
            },
        )

        assertEquals(0, canonicalized)
        assertEquals("普通 PARALLEL 调用即使同名同参也不合并", 2, groups.size)
    }

    @Test
    fun `single call window does not canonicalize`() {
        var canonicalized = 0
        val groups = r22Group(listOf(r22Call("call-1", "read_file", "{\"path\":\"a\"}"))) {
            canonicalized++
            it.input
        }
        assertEquals(0, canonicalized)
        assertEquals(1, groups.size)
    }

    /**
     * 微基准：8 个不同名字、各 8KB 参数的调用。旧写法对每个调用都规范化参数，新写法一个都不做。
     * 只打印同一次运行里的对比数字，不做绝对性能断言（机器不同不可比）。
     */
    @Test
    fun `benchmark grouping skips avoidable canonicalization`() {
        val payload = "x".repeat(8 * 1024)
        val calls = (1..8).map { index ->
            r22Call("call-$index", "tool_$index", "{\"path\":\"p$index\",\"content\":\"$payload\"}")
        }
        fun measure(rounds: Int, body: () -> Unit): Long {
            body() // warm up
            val start = System.nanoTime()
            repeat(rounds) { body() }
            return (System.nanoTime() - start) / rounds
        }

        val skipped = measure(200) {
            r22Group(calls) { canonicalToolArguments(it.input) ?: it.input }
        }
        // 强制每个调用都规范化参数：等价于旧写法（旧代码只看 executionMode，不看名字出现过几次）
        val forced = measure(200) {
            groupParallelCalls(
                calls = calls,
                nameOf = { "" },
                deduplicated = { true },
                canonicalKey = { canonicalToolArguments(it.input) ?: it.input },
            )
        }
        println("r22 grouping ns/round: skip-when-unique=$skipped always-canonicalize=$forced")
        assertTrue(skipped in 0..forced)
    }

    // ------------------------------------------------------------ 事件合并

    @Test
    fun `collapsed duplicate calls commit lifecycle once per group`() = runTest {
        val executions = AtomicInteger()
        val read = r22Tool("read_file", ToolExecutionMode.PARALLEL_DEDUPLICATED) {
            executions.incrementAndGet()
            delay(50)
            listOf(UIMessagePart.Text("same body"))
        }
        val provider = R22FakeProvider()
        provider.result(
            r22Assistant(
                r22Call("call-1", "read_file", "{\"path\":\"a\"}"),
                r22Call("call-2", "read_file", "{\"path\":\"a\"}"),
                r22Call("call-3", "read_file", "{\"path\":\"a\"}"),
                r22Call("call-4", "read_file", "{\"path\":\"a\"}"),
            )
        )
        provider.result(r22Assistant())

        val events = runAgent(
            AgentRequest(
                provider = provider,
                providerSetting = ProviderSetting.OpenAI(),
                messages = listOf(UIMessage.user("hi")),
                tools = listOf(read),
                stream = false,
                maxSteps = 4,
            ),
            R22Hooks(model),
        ).toList()

        assertEquals("相同调用只执行一次", 1, executions.get())
        val finished = events.last() as AgentEvent.Finished
        val tools = finished.message.getTools()
        assertEquals(4, tools.size)
        assertTrue(tools.all { it.isExecuted })
        assertTrue(tools.all { it.startedAt != null })
        assertTrue("四个调用共用同一份结果", tools.all { it.output == tools.first().output })

        // 与 ChatService 相同的判定：工具状态变化才会落库。旧写法 4 次 Started + 4 次 Completed = 8 次，
        // 合并后一组一次 Started、一次 Completed
        assertEquals(2, r22LifecycleCommits(events))
    }

    @Test
    fun `distinct parallel calls keep their own start and result`() = runTest {
        val started = AtomicInteger()
        val read = r22Tool("read_file", ToolExecutionMode.PARALLEL_DEDUPLICATED) { args ->
            started.incrementAndGet()
            delay(20)
            listOf(UIMessagePart.Text("body:$args"))
        }
        val provider = R22FakeProvider()
        provider.result(
            r22Assistant(
                r22Call("call-1", "read_file", "{\"path\":\"a\"}"),
                r22Call("call-2", "read_file", "{\"path\":\"b\"}"),
            )
        )
        provider.result(r22Assistant())

        val events = runAgent(
            AgentRequest(
                provider = provider,
                providerSetting = ProviderSetting.OpenAI(),
                messages = listOf(UIMessage.user("hi")),
                tools = listOf(read),
                stream = false,
                maxSteps = 4,
            ),
            R22Hooks(model),
        ).toList()

        assertEquals(2, started.get())
        val tools = (events.last() as AgentEvent.Finished).message.getTools()
        assertEquals(setOf("call-1", "call-2"), tools.map { it.toolCallId }.toSet())
        assertTrue(tools.all { it.startedAt != null })
        assertTrue(tools.all { it.finishedAt != null })
        assertNotNull(tools.first().startedAt)
        assertTrue("不同参数不能共用结果", tools[0].output != tools[1].output)
        // 两个不同参数各自成组：每组 Started + Completed 各一次
        assertEquals(4, r22LifecycleCommits(events))
    }
}

private fun r22Call(id: String, name: String, input: String) = UIMessagePart.Tool(
    toolCallId = id,
    toolName = name,
    input = input,
)

private fun r22Group(
    calls: List<UIMessagePart.Tool>,
    canonicalKey: (UIMessagePart.Tool) -> String,
) = groupParallelCalls(
    calls = calls,
    nameOf = { it.toolName },
    deduplicated = { true },
    canonicalKey = canonicalKey,
)

private fun r22Assistant(vararg parts: UIMessagePart) =
    UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

private fun r22Tool(
    name: String,
    executionMode: ToolExecutionMode,
    execute: suspend (JsonElement) -> List<UIMessagePart>,
) = Tool(
    name = name,
    description = name,
    executionMode = executionMode,
    execute = execute,
)

/**
 * 和 ChatService 处理 [AgentEvent.MessageUpdated] 时一样的口径：工具的开始/完成状态相对上一次
 * 已提交的状态发生变化，就记一次提交（生产里每一次都会 flush 落库）。
 */
private fun r22LifecycleCommits(events: List<AgentEvent>): Int {
    val committed = HashMap<String, Pair<Instant?, Boolean>>()
    var count = 0
    events.forEach { event ->
        if (event !is AgentEvent.MessageUpdated) return@forEach
        val tools = event.message.getTools()
        val changed = tools.any { tool ->
            val previous = committed[tool.toolCallId]
            (tool.startedAt != null && previous?.first != tool.startedAt) ||
                (tool.isExecuted && previous?.second != true)
        }
        if (changed) {
            count++
            tools.forEach { tool -> committed[tool.toolCallId] = tool.startedAt to tool.isExecuted }
        }
    }
    return count
}

private class R22FakeProvider : Provider<ProviderSetting> {
    private val results = ArrayDeque<TextGenerationResult>()

    fun result(message: UIMessage) {
        results += TextGenerationResult(id = "r22-result", model = "r22-model", message = message)
    }

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = results.removeFirst()

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = error("这些用例只用非流式结果")
}

private class R22Hooks(private val model: Model) : AgentHooks {
    override suspend fun prepareStep(messages: List<UIMessage>) =
        StepRequest(messages, TextGenerationParams(model = model))
}
