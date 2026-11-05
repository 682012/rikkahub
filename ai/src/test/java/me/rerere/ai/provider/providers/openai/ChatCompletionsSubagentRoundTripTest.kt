package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.agent.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Simulated strict upstream, real nonstream agent loop/parser/serializer. No HTTP or API cost. */
class ChatCompletionsSubagentRoundTripTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    private val tool = Tool(name = "read", description = "read fixture") { listOf(UIMessagePart.Text("contents")) }
    private fun params(model: String, tools: List<Tool> = listOf(tool)) = TextGenerationParams(
        model = Model(modelId = model, abilities = listOf(ModelAbility.TOOL)), tools = tools)
    private fun request(messages: List<UIMessage>, params: TextGenerationParams, setting: ProviderSetting.OpenAI): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildChatCompletionRequest", List::class.java,
            TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(api, messages, params, setting, false) as JsonObject
    }
    private fun oldTurn() = UIMessage(role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Reasoning(reasoning = "previous turn"), UIMessagePart.Text("answer")))
    @Test fun `DeepSeek relay with tools replays all turns despite history switch`() {
        val setting = ProviderSetting.OpenAI(baseUrl = "https://relay.example/v1", includeHistoryReasoning = false)
        val body = request(listOf(UIMessage.user("old"), oldTurn(), UIMessage.user("next")), params("relay/deepseek-v4-pro"), setting)
        assertEquals("previous turn", body["messages"]!!.jsonArray[1].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
    }
    @Test fun `DeepSeek without tools still honors history switch`() {
        val setting = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1", includeHistoryReasoning = false)
        val body = request(listOf(UIMessage.user("old"), oldTurn(), UIMessage.user("next")), params("deepseek-flash", emptyList()), setting)
        assertFalse(body["messages"]!!.jsonArray[1].jsonObject.containsKey("reasoning_content"))
    }
    @Test fun `nonstream child finishes two tool steps without dropping reasoning or replaying tools`() = runBlocking {
        val setting = ProviderSetting.OpenAI(baseUrl = "https://relay.example/v1", includeHistoryReasoning = false)
        val p = params("opaque-model-alias")
        var requests = 0; var executions = 0
        val provider = object : Provider<ProviderSetting> {
            override suspend fun listModels(providerSetting: ProviderSetting) = emptyList<Model>()
            override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = error("child must be nonstream")
            override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult {
                val wire = request(messages, params, setting)["messages"]!!.jsonArray
                wire.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "assistant" }.forEach {
                    check(it["reasoning_content"]?.jsonPrimitive?.content?.isNotEmpty() == true) { "reasoning_content must be passed back" }
                }
                val step = ++requests
                val parsed = ChatCompletionsAPI::class.java.getDeclaredMethod("parseMessage", JsonObject::class.java).apply { isAccessible = true }
                    .invoke(api, buildJsonObject {
                        put("role", "assistant"); put("reasoning_content", "reason $step"); put("content", if (step < 3) "read $step" else "finished")
                        if (step < 3) putJsonArray("tool_calls") { add(buildJsonObject {
                            put("id", "call_$step"); put("type", "function")
                            putJsonObject("function") { put("name", "read"); put("arguments", "{\"step\":$step}") }
                        }) }
                    }) as UIMessage
                return TextGenerationResult(id = "response_$step", model = p.model.modelId, message = parsed)
            }
        }
        val t = Tool(name = "read", description = "fixture") { executions++; listOf(UIMessagePart.Text("contents")) }
        val result = runAgent(AgentRequest(provider, setting, listOf(UIMessage.user("inspect twice")), tools = listOf(t), stream = false, maxSteps = 4, retry = RetryPolicy.NONE),
            object : AgentHooks { override suspend fun prepareStep(messages: List<UIMessage>) = StepRequest(messages, p) }).last() as AgentEvent.Finished
        assertEquals(AgentFinishReason.Completed, result.reason)
        assertEquals(3, requests); assertEquals(2, executions)
        assertTrue(result.message.toText().endsWith("finished"))
    }
}
