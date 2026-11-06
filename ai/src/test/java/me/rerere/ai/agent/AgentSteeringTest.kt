package me.rerere.ai.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * m1 运行中插话：AgentLoop 只靠 [AgentHooks.pendingUserParts] 拿用户插话。
 *
 * 全部用真实 runAgent + 假 Provider：断言请求次数、每步实际发给模型的消息、
 * 工具副作用次数，而不是只看最终消息长什么样。
 */
class AgentSteeringTest {
    private val model = Model(modelId = "test-model")

    @Test
    fun `user part added after a plain text answer is seen by the next step`() = runTest {
        val provider = SteeringProvider(model, text = "first answer", text2 = "second answer")
        val hooks = SteeringHooks(model, userBatches = listOf(listOf(userNotice("插一句"))))

        val events = runAgent(request(provider), hooks).toList()

        // 第一个响应是纯文本（没有工具调用）：用户插话必须让它再走一步，而不是直接 Completed
        assertEquals(2, provider.requests.size)
        val secondRequest = provider.requests[1].messages
        assertTrue(
            "user steering text must be visible to the second request",
            secondRequest.any { message ->
                message.parts.any { it is UIMessagePart.Notice && it.text == "插一句" }
            },
        )
        val finished = events.last() as AgentEvent.Finished
        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertTrue(finished.message.parts.any { it is UIMessagePart.Notice && it.text == "插一句" })
    }

    @Test
    fun `no user part means no extra provider request`() = runTest {
        val provider = SteeringProvider(model, text = "only answer")
        val hooks = SteeringHooks(model)

        val events = runAgent(request(provider), hooks).toList()

        assertEquals(1, provider.requests.size)
        assertEquals(AgentFinishReason.Completed, (events.last() as AgentEvent.Finished).reason)
    }

    @Test
    fun `ordinary background notice does not start an extra step`() = runTest {
        val provider = SteeringProvider(model, text = "only answer")
        val hooks = SteeringHooks(model, backgroundBatches = listOf(listOf(backgroundNotice("后台命令结束"))))

        val events = runAgent(request(provider), hooks).toList()

        // 后台通知随本轮送进去，但不能像用户插话那样催出第二个请求
        assertEquals(1, provider.requests.size)
        assertEquals(AgentFinishReason.Completed, (events.last() as AgentEvent.Finished).reason)
    }

    @Test
    fun `user part is not consumed when the step budget is already used up`() = runTest {
        val provider = SteeringProvider(model, text = "only answer")
        val hooks = SteeringHooks(model, userBatches = listOf(listOf(userNotice("太晚的插话"))))

        val events = runAgent(request(provider, maxSteps = 1), hooks).toList()

        assertEquals(1, provider.requests.size)
        // 最后一步没有待执行工具、模型已经答完：这就是一轮正常结束，不能被当成超预算
        assertEquals(AgentFinishReason.Completed, (events.last() as AgentEvent.Finished).reason)
        // 超预算时不再问：这一批插话留在队列里，下一轮照常发
        assertEquals(1, hooks.remainingUserBatches)
    }

    @Test
    fun `steering does not replay tool side effects`() = runTest {
        val provider = SteeringProvider(model, text = "answer after tool", withTool = true)
        val hooks = SteeringHooks(model, userBatches = listOf(listOf(userNotice("再补一句"))))
        var effects = 0
        val tool = Tool(
            name = "write_thing",
            description = "writes once",
            execute = { _: JsonElement ->
                effects++
                listOf(UIMessagePart.Text("""{"ok":true}"""))
            },
        )

        val events = runAgent(request(provider, tools = listOf(tool)), hooks).toList()

        assertEquals("tool must run exactly once", 1, effects)
        // 工具一步 + 文本一步（插话让它多走一步），工具结果不会被重放
        assertEquals(2, provider.requests.size)
        assertTrue(events.last() is AgentEvent.Finished)
    }

    private fun request(
        provider: Provider<ProviderSetting>,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
    ) = AgentRequest(
        provider = provider,
        providerSetting = ProviderSetting.OpenAI(),
        messages = listOf(UIMessage.user("hi")),
        tools = tools,
        stream = false,
        maxSteps = maxSteps,
    )

    private fun userNotice(text: String) = UIMessagePart.Notice(
        title = "用户插话",
        text = text,
        metadata = buildJsonObject { put("source", "USER") },
    )

    private fun backgroundNotice(text: String) = UIMessagePart.Notice(title = "后台", text = text)
}

/**
 * 假 Provider：第 n 次请求返回第 n 个预设回复，用完后一直重复最后一个。
 * [withTool] 时第一次返回一个工具调用，之后才返回文本。
 */
private class SteeringProvider(
    private val model: Model,
    private val text: String,
    private val text2: String = text,
    private val withTool: Boolean = false,
) : Provider<ProviderSetting> {
    data class Request(val messages: List<UIMessage>, val params: TextGenerationParams)

    val requests = mutableListOf<Request>()

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = listOf(model)

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        requests += Request(messages, params)
        val parts = when {
            withTool && requests.size == 1 -> listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "write_thing",
                    input = "{}",
                )
            )

            requests.size <= 1 -> listOf(UIMessagePart.Text(text))
            else -> listOf(UIMessagePart.Text(text2))
        }
        return TextGenerationResult(
            id = "result-${requests.size}",
            model = model.modelId,
            message = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow { error("streaming not used in this test") }
}

/** 记录每一步实际发送的消息；[userBatches] 只有用户插话，[backgroundBatches] 是后台通知 */
private class SteeringHooks(
    private val model: Model,
    private val userBatches: List<List<UIMessagePart>> = emptyList(),
    private val backgroundBatches: List<List<UIMessagePart>> = emptyList(),
) : AgentHooks {
    private val users = ArrayDeque(userBatches)
    private val background = ArrayDeque(backgroundBatches)
    var userCalls = 0
    val prepared = mutableListOf<List<UIMessage>>()

    override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
        prepared += messages
        return StepRequest(messages, TextGenerationParams(model = model))
    }

    /** 用户是在模型开始生成之后才发消息的：第一步请求之前的探测一律返回空 */
    override suspend fun pendingUserParts(): List<UIMessagePart> {
        userCalls++
        if (prepared.isEmpty()) return emptyList()
        return users.removeFirstOrNull().orEmpty()
    }

    /** 还没被取走的用户插话批次数 */
    val remainingUserBatches: Int get() = users.size

    override suspend fun pendingParts(): List<UIMessagePart> =
        background.removeFirstOrNull().orEmpty()
}
