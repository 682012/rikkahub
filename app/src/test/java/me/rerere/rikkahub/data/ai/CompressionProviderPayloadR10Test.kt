package me.rerere.rikkahub.data.ai

import java.lang.reflect.Method
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.rikkahub.data.ai.prompts.buildCompressPrompt
import me.rerere.rikkahub.data.datastore.AutoCompressSettings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.service.autoCompressionPlan
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩请求在真实 provider 序列化层上的回归（r10 图片压缩修复）。
 *
 * 用户看到的错误是上游 400 `Images in assistant messages are not supported`：历史里只要有一条助手图片，
 * OpenAI 兼容转发层（LiteLLM 之类）就整条拒绝。修复把附件历史压成角色标记的纯文本
 * （[CompactionRequestMessages]），本文件不重复那层单元断言，而是拿**同一个 helper 的输出**过真实 provider
 * 的请求组装函数，检查最终要上行的 JSON：
 *
 * - OpenAI chat / OpenAI responses / Claude / Google 四种序列化都不含 assistant 图片、不含 base64；
 * - system 只装助手提示词，历史里的 user/tool 文字不会被提升进 system（防止权限/指令被抬高）；
 * - 工具调用结果以 `[tool ...]` 文本进记录，不再作为 tool 角色消息或 tool_use/tool_result 块上行；
 * - 原会话消息和图片引用一个字节都没动；
 * - 手动与自动压缩拿到的是同一份 helper 产物。
 *
 * provider 的请求组装函数是 private（ResponseAPI 是 internal），沿用本仓库既有测试的做法用反射调用；
 * 反射按「方法名前缀 + 参数个数」查找，避免 Kotlin internal 名字修饰带来的方法名差异。
 * 走的是纯组装路径，不发网络请求，也不经过 android.util.Log 所在的 generateText/streamText。
 */
class CompressionProviderPayloadR10Test {

    private companion object {
        /** 助手生成的图片（内联 base64），修复前就是它触发上游 400 */
        const val GENERATED_IMAGE =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg"

        /** base64 载荷的特征片段：出现在要上行的 JSON 里就说明又原样带图了 */
        const val BASE64_MARKER = "iVBORw0KGgo"

        const val SYSTEM_PROMPT = "You are a terse assistant. SYSTEM_ONLY_RULE"
        const val REQUIREMENT = "导出按钮放在右下角，需求要保留 KEEP_REQUIREMENT"
        const val TOOL_OUTPUT = "BUILD SUCCESSFUL in 12s; TOOL_RESULT_KEEP"
        const val TOOL_INPUT = "{\"command\":\"gradlew assembleDebug\"}"
    }

    private val model = Model(
        modelId = "gpt-test",
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        outputModalities = listOf(Modality.TEXT),
        abilities = listOf(ModelAbility.TOOL),
    )

    private val openAiChat = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    private val openAiResponses = ResponseAPI(OkHttpClient())
    private val claude = ClaudeProvider(OkHttpClient())
    private val google = GoogleProvider(OkHttpClient())

    // ---- 样本历史与 helper 产物 ----

    private fun params(
        tools: List<Tool> = emptyList(),
        disableToolCalls: Boolean = false,
    ) = TextGenerationParams(
        model = model,
        tools = tools,
        disableToolCalls = disableToolCalls,
    )

    private fun compressPrompt() = buildCompressPrompt(additionalPrompt = "", locale = "English")

    private fun sampleHistory(): List<UIMessage> = listOf(
        UIMessage.user(REQUIREMENT),
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("截图已完成，见下图"),
                UIMessagePart.Image(GENERATED_IMAGE),
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "workspace_shell",
                    input = TOOL_INPUT,
                    output = listOf(UIMessagePart.Text(TOOL_OUTPUT)),
                ),
            ),
        ),
        UIMessage.user("再改一版"),
    )

    /** 手动压缩与自动压缩最终都调用 ChatRequestFactory.generateCompressionSummary，这里直接取它用的组装器 */
    private fun compaction(history: List<UIMessage>): List<UIMessage> =
        CompactionRequestMessages.build(
            system = UIMessage.system(SYSTEM_PROMPT),
            history = history,
            compressPrompt = compressPrompt(),
        )

    // ---- 四种协议的真实请求体 ----

    private fun chatBody(
        messages: List<UIMessage>,
        generationParams: TextGenerationParams = params(),
    ): JsonObject = invokeJson(
        target = openAiChat,
        methodName = "buildChatCompletionRequest",
        argCount = 4,
        args = arrayOf(messages, generationParams, ProviderSetting.OpenAI(), false),
    )

    private fun responsesBody(
        messages: List<UIMessage>,
        generationParams: TextGenerationParams = params(),
    ): JsonObject = invokeJson(
        target = openAiResponses,
        methodName = "buildRequestBody",
        argCount = 4,
        args = arrayOf(responseCapabilities(), messages, generationParams, false),
    )

    private fun claudeBody(
        messages: List<UIMessage>,
        generationParams: TextGenerationParams = params(),
    ): JsonObject = invokeJson(
        target = claude,
        methodName = "buildMessageRequest",
        argCount = 4,
        args = arrayOf(ProviderSetting.Claude(), messages, generationParams, false),
    )

    private fun googleBody(
        messages: List<UIMessage>,
        generationParams: TextGenerationParams = params(),
    ): JsonObject = invokeJson(
        target = google,
        methodName = "buildCompletionRequestBody",
        argCount = 2,
        args = arrayOf(messages, generationParams),
    )

    /**
     * ResponseAPI.buildRequestBody 需要一个 internal 的 capabilities 值。
     * 先走生产同款入口 resolveResponseProviderCapabilities(host)，拿不到再退到无参构造，
     * 两条都失败就明确报错，避免静默跳过断言。
     */
    private fun responseCapabilities(): Any {
        val facade = runCatching {
            Class.forName("me.rerere.ai.provider.providers.openai.ResponseAPIKt")
        }.getOrNull()
        facade?.declaredMethods
            ?.firstOrNull { it.name.startsWith("resolveResponseProviderCapabilities") && it.parameterCount == 1 }
            ?.let { resolver ->
                resolver.isAccessible = true
                resolver.invoke(null, "api.openai.com")?.let { return it }
            }

        val cls = Class.forName("me.rerere.ai.provider.providers.openai.ResponseProviderCapabilities")
        val ctor = cls.declaredConstructors.firstOrNull { it.parameterCount == 0 }
            ?: error("ResponseProviderCapabilities 没有无参构造，无法组装 responses 请求体")
        ctor.isAccessible = true
        return ctor.newInstance()
    }

    private fun invokeJson(
        target: Any,
        methodName: String,
        argCount: Int,
        args: Array<Any?>,
    ): JsonObject {
        val method: Method = target.javaClass.declaredMethods.firstOrNull { candidate ->
            (candidate.name == methodName || candidate.name.startsWith(methodName + "$")) &&
                candidate.parameterCount == argCount
        } ?: error("${target.javaClass.name} 上找不到 $methodName($argCount 个参数) 的请求组装函数")
        method.isAccessible = true
        val result = method.invoke(target, *args)
        return result as? JsonObject
            ?: error("${target.javaClass.name}.$methodName 没有返回 JsonObject")
    }

    // ---- 断言工具 ----

    private fun JsonObject.contentText(): String {
        val content: JsonElement = get("content") ?: return ""
        return if (content is JsonPrimitive) content.content else content.toString()
    }

    private fun JsonObject.role(): String = get("role")?.jsonPrimitive?.content.orEmpty()

    /** 压缩请求里不允许出现任何图片载荷；同时也不允许出现各类协议专用的图片结构 */
    private fun assertNoImagePayloads(route: String, body: JsonObject) {
        val text = body.toString()
        assertFalse("$route: 压缩请求不能带内联 base64", text.contains("base64,"))
        assertFalse("$route: 压缩请求不能带图片字节", text.contains(BASE64_MARKER))
        assertFalse("$route: 压缩请求不能有 image_url 内容块", text.contains("image_url"))
        assertFalse("$route: 压缩请求不能有 input_image 内容块", text.contains("input_image"))
        assertFalse("$route: 压缩请求不能有 Gemini inlineData", text.contains("inlineData"))
        assertFalse("$route: 压缩请求不能有 Claude image block", text.contains("\"image\""))
        assertFalse("$route: 压缩请求不能有 Claude base64 source", text.contains("\"source\""))
    }

    private fun assertTranscriptKeepsToolOutput(route: String, text: String) {
        assertTrue("$route: 历史要有角色标记", text.contains("<conversation_transcript>"))
        assertTrue("$route: 用户需求要保留", text.contains("[user]\n$REQUIREMENT"))
        assertTrue("$route: 助手发言要标出角色", text.contains("[assistant]"))
        assertTrue(
            "$route: 图片要换成带来源的说明",
            text.contains("[image, image/png generated by the assistant (base64 data omitted)]"),
        )
        assertTrue(
            "$route: 工具结果要能被概括",
            text.contains("[tool \"workspace_shell\" id=call_1] result: $TOOL_OUTPUT"),
        )
        assertTrue("$route: 压缩指令排在记录之后", text.contains("Stop the current task and write a structured summary"))
    }

    // ---- OpenAI chat completions ----

    @Test
    fun `openai chat compaction request carries no assistant image and no base64`() {
        val body = chatBody(compaction(sampleHistory()))

        assertNoImagePayloads("openai-chat", body)
        val text = body.toString()
        assertFalse("工具结果不能再作为 tool 角色消息上行", text.contains("\"role\":\"tool\""))
        assertFalse("历史工具调用不能变成 tool_calls", text.contains("tool_calls"))

        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            "压缩请求只有 system + 一条 user",
            listOf("system", "user"),
            messages.map { it.role() },
        )
        assertEquals("system 只能是指令本身", SYSTEM_PROMPT, messages[0].contentText())
        assertFalse("用户需求不能被提升进 system", messages[0].contentText().contains(REQUIREMENT))
        assertFalse("工具输出不能被提升进 system", messages[0].contentText().contains(TOOL_OUTPUT))

        assertTranscriptKeepsToolOutput("openai-chat", messages[1].contentText())
    }

    // ---- OpenAI responses ----

    @Test
    fun `openai responses compaction request keeps system in instructions and transcript in a user item`() {
        val body = responsesBody(compaction(sampleHistory()))

        assertNoImagePayloads("openai-responses", body)
        val text = body.toString()
        assertFalse("历史工具结果不能变成 function_call_output", text.contains("function_call_output"))
        assertFalse("历史工具调用不能变成 function_call", text.contains("function_call"))

        assertEquals(
            "system 必须留在 instructions，不能塞进 input",
            SYSTEM_PROMPT,
            body["instructions"]?.jsonPrimitive?.content,
        )
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        assertTrue("压缩请求里不能有 assistant 项", input.none { it.role() == "assistant" })
        assertEquals("input 里只有一条 user", listOf("user"), input.map { it.role() })
        assertTranscriptKeepsToolOutput("openai-responses", input[0].contentText())
    }

    // ---- Claude ----

    @Test
    fun `claude compaction request has no image tool_use or tool_result blocks`() {
        val body = claudeBody(compaction(sampleHistory()))

        assertNoImagePayloads("claude", body)
        val text = body.toString()
        assertFalse("历史工具调用不能变成 tool_use", text.contains("tool_use"))
        assertFalse("历史工具结果不能变成 tool_result", text.contains("tool_result"))

        val system = body["system"]!!.jsonArray
            .joinToString("\n") { it.jsonObject["text"]!!.jsonPrimitive.content }
        assertEquals("system 只能是指令本身", SYSTEM_PROMPT, system)

        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("Claude 只收一条 user 消息", listOf("user"), messages.map { it.role() })
        val blocks = messages[0]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("内容块只能是文本", listOf("text"), blocks.map { it["type"]!!.jsonPrimitive.content })
        assertTranscriptKeepsToolOutput(
            "claude",
            blocks.joinToString("\n") { it["text"]!!.jsonPrimitive.content },
        )
    }

    // ---- Google ----

    @Test
    fun `google compaction request has no inline data and no function response`() {
        val body = googleBody(compaction(sampleHistory()))

        assertNoImagePayloads("google", body)
        val text = body.toString()
        assertFalse("历史工具结果不能变成 functionResponse", text.contains("functionResponse"))
        assertFalse("历史工具调用不能变成 functionCall", text.contains("functionCall"))

        val system = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray
            .joinToString("\n") { it.jsonObject["text"]!!.jsonPrimitive.content }
        assertEquals("systemInstruction 只能是指令本身", SYSTEM_PROMPT, system)

        val contents = body["contents"]!!.jsonArray.map { it.jsonObject }
        assertEquals("只能有一条 user content，不能有 model 角色", listOf("user"), contents.map { it.role() })
        val parts = contents[0]["parts"]!!.jsonArray.map { it.jsonObject }
        assertTrue("parts 只能是文本", parts.all { it["text"] != null })
        assertTranscriptKeepsToolOutput(
            "google",
            parts.joinToString("\n") { it["text"]!!.jsonPrimitive.content },
        )
    }

    // ---- 反向对照：确认上面的断言确实检查到真实内容 ----

    @Test
    fun `the pre fix raw history request would still leak the assistant image`() {
        // 修复前 generateCompressionSummary 直接把原始历史发给 provider。
        // 这条对照证明「没有图片/base64」不是空跑出来的：同一套序列化、同一份断言对象，
        // 换成原始历史就会带上 base64 和 assistant 图片。
        val raw = listOf(UIMessage.system(SYSTEM_PROMPT)) + sampleHistory() + UIMessage.user(compressPrompt())
        val body = chatBody(raw)
        val text = body.toString()

        assertTrue("原始历史会带上内联 base64", text.contains("base64,"))
        assertTrue("原始历史会带上图片字节", text.contains(BASE64_MARKER))
        assertTrue("原始历史会有 image_url 内容块", text.contains("\"image_url\""))
        val roles = body["messages"]!!.jsonArray.map { it.jsonObject.role() }
        assertTrue("原始历史会有 assistant 消息", roles.contains("assistant"))
        assertTrue("原始历史会有 tool 角色消息", roles.contains("tool"))
    }

    // ---- 原消息与图片引用不被改动 ----

    @Test
    fun `compaction never mutates the stored messages or the image reference`() {
        val history = sampleHistory()
        val snapshot = history.toList()
        val imageBefore = history[1].parts.filterIsInstance<UIMessagePart.Image>().single()

        val payload = compaction(history)
        chatBody(payload)
        responsesBody(payload)
        claudeBody(payload)
        googleBody(payload)
        CompactionRequestMessages.transcript(history)

        assertEquals("原消息列表必须原样保留", snapshot, history)
        val imageAfter = history[1].parts.filterIsInstance<UIMessagePart.Image>().single()
        assertSame("图片引用必须还是同一个对象", imageBefore, imageAfter)
        assertEquals("图片 URL 不能被改写", GENERATED_IMAGE, imageAfter.url)
        assertTrue("助手正文不能被换成转写文本", history[1].toText().contains("截图已完成"))
        assertFalse("原消息里不能混进转写文本", history.any { it.toText().contains("<conversation_transcript>") })
    }

    // ---- 手动与自动压缩共用实际 helper ----

    @Test
    fun `manual and automatic compression serialize the same text only request`() {
        val nodes = listOf(
            UIMessage.user(REQUIREMENT).toMessageNode(),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("截图已完成，见下图"),
                    UIMessagePart.Image(GENERATED_IMAGE),
                    UIMessagePart.Tool(
                        toolCallId = "call_1",
                        toolName = "workspace_shell",
                        input = TOOL_INPUT,
                        output = listOf(UIMessagePart.Text(TOOL_OUTPUT)),
                    ),
                ),
            ).toMessageNode(),
            UIMessage.user("再改一版").toMessageNode(),
        )
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = nodes)
        // 自动：maybeAutoCompress 用 autoCompressionPlan 给出的历史
        val plan = autoCompressionPlan(
            conversation = conversation,
            config = AutoCompressSettings(enabled = true),
            estimateWhenUsageMissing = true,
        )!!
        // 手动：compressConversation 在 keepRecentMessages = 0 时压缩全部消息
        val manual = conversation.currentMessages.take(nodes.size)

        val payloads = linkedMapOf(
            "auto" to compaction(plan.messages),
            "manual" to compaction(manual),
        )
        for ((route, payload) in payloads) {
            assertEquals("$route: 只有 system + user", listOf(MessageRole.SYSTEM, MessageRole.USER), payload.map { it.role })
            assertTrue("$route: 不能有 assistant 消息", payload.none { it.role == MessageRole.ASSISTANT })
            assertTrue("$route: 不能有图片 part", payload.none { message -> message.parts.any { it is UIMessagePart.Image } })
            assertNoImagePayloads("$route/openai-chat", chatBody(payload))
            assertNoImagePayloads("$route/openai-responses", responsesBody(payload))
            assertNoImagePayloads("$route/claude", claudeBody(payload))
            assertNoImagePayloads("$route/google", googleBody(payload))
        }
        // 自动压缩只丢最后一轮用户输入，压缩范围其余部分和手动压缩一致
        assertEquals(manual.size - 1, plan.messages.size)
    }

    // ---- 保留工具定义时仍然禁止调用 ----

    @Test
    fun `retained tool definitions never let the summary request call tools`() {
        val tools = listOf(
            Tool(name = "workspace_shell", description = "run a shell command") { emptyList() },
        )
        // ChatRequestFactory 对 OpenAI/Claude 保留定义 + disableToolCalls，其余协议直接不带工具
        val chat = chatBody(compaction(sampleHistory()), params(tools = tools, disableToolCalls = true))
        assertEquals("none", chat["tool_choice"]?.jsonPrimitive?.content)
        assertTrue("工具定义仍保留在缓存前缀里", chat["tools"]!!.jsonArray.isNotEmpty())
        assertNoImagePayloads("openai-chat/tools", chat)

        val anthropic = claudeBody(compaction(sampleHistory()), params(tools = tools, disableToolCalls = true))
        assertEquals(
            "none",
            anthropic["tool_choice"]?.jsonObject?.get("type")?.jsonPrimitive?.content,
        )
        assertTrue("工具定义仍保留在缓存前缀里", anthropic["tools"]!!.jsonArray.isNotEmpty())
        assertNoImagePayloads("claude/tools", anthropic)
    }
}
