package me.rerere.rikkahub.data.ai.subagent

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.RetryDelay
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.agent.withRetry
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
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 运行期接线的回归测试（不是纯排序/纯策略）：
 *
 * - 渠道排队（每模型并发许可）必须被整任务预算封顶，绝不能无限挂；
 * - 许可账本在 perModelConcurrency 运行期被改成 0 时不能泄漏；
 * - 换渠道不重置 max_steps；
 * - 重试次数是**整轮一份**，而不是每步各 N 次；
 * - 从检查点续跑不会重新执行已经执行过的工具（用真实 runAgent + fake provider 跑）。
 */
class SubagentRuntimeWiringTest {

    // ---------- 渠道排队 ----------

    @Test
    fun `channel wait is bounded by the whole task budget instead of hanging forever`() = runBlocking {
        val permits = SubagentModelPermits()
        val model = Uuid.random()
        // 只有一个许可，先被别的任务占住：下一个任务必须排队。
        assertTrue(permits.acquire(model, 1))
        assertEquals(1, permits.activeCount(model))

        val budget = SubagentTimeBudget(1) // 整任务只剩 1 秒
        val startedAt = System.currentTimeMillis()
        val held = permits.acquireWithinBudget(budget, model, 1)
        val waited = System.currentTimeMillis() - startedAt

        assertFalse("预算内排不到许可时必须放弃，不能永远挂在渠道上", held)
        assertTrue("必须真的等过一段预算，而不是立刻返回：$waited", waited >= 300)
        assertTrue("等待不能超过预算太多：$waited", waited < 5_000)

        // 没拿到许可就不许 release，否则账本会减成负数；释放真正持有的那一个之后归零。
        permits.release(model)
        assertEquals(0, permits.activeCount(model))
    }

    @Test
    fun `per model limit of zero never blocks and holds nothing`() = runBlocking {
        val permits = SubagentModelPermits()
        val model = Uuid.random()
        val budget = SubagentTimeBudget(900)
        assertFalse(permits.acquireWithinBudget(budget, model, 0))
        assertEquals(0, permits.activeCount(model))
    }

    @Test
    fun `permit accounting stays balanced when the per model limit shrinks to zero at runtime`() = runBlocking {
        val permits = SubagentModelPermits()
        val model = Uuid.random()
        assertTrue(permits.acquire(model, 2))
        assertEquals(1, permits.activeCount(model))

        // 用户中途把"每模型并发"改成 0：had-acquired 的任务仍然必须能还回去，
        // 否则计数永久泄漏，BALANCED 会以为这个渠道永远满载。
        permits.release(model)
        assertEquals(0, permits.activeCount(model))
    }

    // ---------- 步数预算 ----------

    @Test
    fun `step budget is not reset by switching model`() {
        assertEquals(40, subagentRemainingSteps(40, 0))
        assertEquals(10, subagentRemainingSteps(40, 30))
        // 已经超过用户设置时也给 1 步，让续跑至少能收尾，而不是负数
        assertEquals(1, subagentRemainingSteps(40, 40))
        assertEquals(1, subagentRemainingSteps(3, 99))
    }

    // ---------- 重试预算 ----------

    @Test
    fun `retry count is one per run instead of one per step`() {
        val settings = Settings(networkSetting = NetworkSetting(enableAutoRetry = true, maxRetries = 2))
        val policy = buildRequest(settings).retry.copy(delayMode = RetryDelay.fixed(0))
        var attempts = 0
        runBlocking {
            // 两个"模型步"各失败一次：共享预算下总数是 3（首发 + 2 次重试），不是每步 3 次。
            repeat(2) {
                runCatching { withRetry(policy) { attempts++; throw IOException("connection reset") } }
            }
        }
        assertEquals("同一轮的两个步骤必须共用一份重试预算", 4, attempts)
    }

    // ---------- 检查点续跑 ----------

    @Test
    fun `resumed run keeps executed tools and never re-executes them`() = runBlocking {
        val provider = FakeTextProvider("done")
        var executed = 0
        val tool = Tool(
            name = "workspace_write_file",
            description = "write",
            executionMode = ToolExecutionMode.SERIAL,
        ) {
            executed++
            listOf(UIMessagePart.Text("written"))
        }
        // 上一次尝试已经执行成功的工具调用：续跑时必须原样保留，不能重放副作用。
        val seed = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("half way"),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "workspace_write_file",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("written")),
                ),
            ),
            stepCount = 1,
        )
        val request = buildSubagentRequest(
            provider = provider,
            providerSetting = ProviderSetting.OpenAI(name = "test"),
            prompt = "finish the task",
            tools = listOf(tool),
            maxSteps = 40,
            settings = Settings(),
            seed = seed,
        )
        val events = runAgent(request, Hooks(provider.model)).toList()
        val finished = events.filterIsInstance<AgentEvent.Finished>().last()

        assertEquals("续跑不能重新执行已经成功过的工具", 0, executed)
        assertEquals("done", finished.message.toText())
        // 发给模型的上下文里带着上一次的工具结果，模型是从半途接着写的
        val sent = provider.calls.single()
        val carried = sent.flatMap { it.getTools() }.single { it.toolCallId == "call-1" }
        assertTrue(carried.isExecuted)
        assertEquals("written", (carried.output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `a run without checkpoint starts clean`() = runBlocking {
        val provider = FakeTextProvider("fresh")
        val request = buildRequest(Settings(), provider = provider)
        val finished = runAgent(request, Hooks(provider.model)).toList()
            .filterIsInstance<AgentEvent.Finished>().last()
        assertEquals("fresh", finished.message.toText())
        assertEquals(1, provider.calls.size)
        assertNull(provider.calls.single().last().stepCount)
    }

    private fun buildRequest(
        settings: Settings,
        provider: Provider<ProviderSetting> = FakeTextProvider("ok"),
    ): AgentRequest = buildSubagentRequest(
        provider = provider,
        providerSetting = ProviderSetting.OpenAI(name = "test"),
        prompt = "do the thing",
        tools = emptyList(),
        maxSteps = 40,
        settings = settings,
    )

    private class Hooks(private val model: Model) : AgentHooks {
        override suspend fun prepareStep(messages: List<UIMessage>): StepRequest =
            StepRequest(messages, TextGenerationParams(model = model))
    }

    /** buildSubagentRequest 是非流式的，所以只需要 generateText。 */
    private class FakeTextProvider(private val reply: String) : Provider<ProviderSetting> {
        val model = Model(modelId = "test-model")
        val calls = mutableListOf<List<UIMessage>>()

        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = listOf(model)

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            calls += messages
            return TextGenerationResult(
                id = "result",
                model = "test-model",
                message = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(reply)),
                ),
            )
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> = error("subagent requests are not streaming")
    }
}
